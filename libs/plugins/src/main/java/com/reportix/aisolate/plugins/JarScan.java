package com.reportix.aisolate.plugins;

import com.reportix.aisolate.plugins.HandlerCandidate.Stage;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import lombok.extern.slf4j.Slf4j;

/**
 * The tool plugins and handlers of an airlock's jars, whatever their package. Every class file is
 * loaded over the runtime's SDK (parent-first, so a jar's {@code AnswerVerifier} is the runtime's)
 * without initialising it; a concrete class implementing {@link ToolPlugin} is a plugin, one
 * implementing a stage's interface ({@link HandlerCandidate.Stage}) is a handler, and each is
 * created through its no-arg constructor, package-private or not. A class that cannot link is
 * skipped, counted in one WARN per jar. A class compiled for a newer Java, a class that is both
 * plugin and handler or implements two stages, or an instance that cannot be created fails the
 * whole scan, because running without it would be a silent change of behaviour. The class loader is
 * never closed: the instances live as long as their catalog keeps them.
 */
@Slf4j
public record JarScan(List<HandlerCandidate> handlers, List<ToolPlugin> plugins) {

  private static final String RULES = "aisolate/rules/";

  /** A rules file is a few KiB; a zip entry can inflate to gigabytes. */
  private static final int MAX_RULES_BYTES = 1024 * 1024;

  public static JarScan of(List<Path> jars) throws IOException {
    return scan(jars, true);
  }

  /**
   * The handlers alone, for a pod that runs no tools: every class is still checked, but no plugin
   * constructor runs, so a plugin that needs the tool server's environment does not fail the pod.
   */
  public static List<HandlerCandidate> handlersOf(List<Path> jars) throws IOException {
    return scan(jars, false).handlers();
  }

  /**
   * The rule sets a jar ships, by path: every {@code .json} directly in {@code aisolate/rules/},
   * one per entity. The reader parses them; this only reads.
   *
   * @throws IllegalStateException naming the file: one in a folder below, or longer than 1 MiB
   */
  public static Map<String, String> ruleFiles(JarFile jar) throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    for (JarEntry entry : Collections.list(jar.entries())) {
      String name = entry.getName();
      if (entry.isDirectory() || !name.startsWith(RULES) || !name.endsWith(".json")) {
        continue;
      }
      requireRuleSetPath(name);
      try (InputStream in = jar.getInputStream(entry)) {
        byte[] content = in.readNBytes(MAX_RULES_BYTES + 1);
        if (content.length > MAX_RULES_BYTES) {
          throw new IllegalStateException(name + ": longer than 1 MiB");
        }
        files.put(name, new String(content, StandardCharsets.UTF_8));
      }
    }
    return files;
  }

  /**
   * @param path a {@code .json} file's path from the root, {@code aisolate/rules/...}
   * @throws IllegalStateException naming the file when it sits in a folder below
   */
  public static void requireRuleSetPath(String path) {
    if (path.indexOf('/', RULES.length()) >= 0) {
      throw new IllegalStateException(path + ": a rule set is rules/<entity>.json");
    }
  }

  private static JarScan scan(List<Path> jars, boolean createPlugins) throws IOException {
    URLClassLoader loader =
        new URLClassLoader(
            jars.stream().map(JarScan::url).toArray(URL[]::new), JarScan.class.getClassLoader());
    List<HandlerCandidate> handlers = new ArrayList<>();
    List<ToolPlugin> plugins = new ArrayList<>();
    for (Path jar : jars) {
      int skipped = 0;
      try (JarFile file = new JarFile(jar.toFile())) {
        List<String> classNames =
            file.stream().map(JarScan::className).flatMap(Optional::stream).toList();
        for (String className : classNames) {
          Optional<Class<?>> type = loadClass(loader, className);
          if (type.isEmpty()) {
            skipped++;
          } else {
            scan(type.get(), handlers, plugins, createPlugins);
          }
        }
      }
      if (skipped > 0) {
        log.warn(
            "skipped {} class(es) of {} that could not link (debug log names them)",
            skipped,
            jar.getFileName());
      }
    }
    return new JarScan(List.copyOf(handlers), List.copyOf(plugins));
  }

  /** The class a jar entry holds, if it is one: {@code com/x/Y.class} to {@code com.x.Y}. */
  private static Optional<String> className(JarEntry entry) {
    String name = entry.getName();
    if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
      return Optional.empty();
    }
    return Optional.of(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
  }

  /**
   * Loaded without initialising it; a class the jar cannot link is skipped, not a failure. One
   * compiled for a newer Java is a failure: a jar built on a newer JDK would otherwise run with
   * none of its classes.
   */
  private static Optional<Class<?>> loadClass(ClassLoader loader, String className) {
    try {
      return Optional.of(Class.forName(className, false, loader));
    } catch (UnsupportedClassVersionError newerJava) {
      throw new IllegalStateException(
          className + " was compiled for a newer Java than this AIsolate runs", newerJava);
    } catch (ClassNotFoundException | LinkageError cannotLink) {
      log.debug("skipping {}: {}", className, cannotLink.toString());
      return Optional.empty();
    }
  }

  private static void scan(
      Class<?> type,
      List<HandlerCandidate> handlers,
      List<ToolPlugin> plugins,
      boolean createPlugins) {
    if (!isInstantiable(type)) {
      return;
    }
    boolean plugin = ToolPlugin.class.isAssignableFrom(type);
    List<Stage> stages =
        Arrays.stream(Stage.values()).filter(s -> s.contract.isAssignableFrom(type)).toList();
    if (stages.size() > 1) {
      throw new IllegalStateException(type.getName() + " implements more than one handler stage");
    }
    if (plugin && !stages.isEmpty()) {
      throw new IllegalStateException(type.getName() + " is both a tool plugin and a handler");
    }
    if (plugin) {
      if (createPlugins) {
        plugins.add((ToolPlugin) create(type));
      }
      return;
    }
    if (stages.isEmpty()) {
      return;
    }
    Stage stage = stages.getFirst();
    Object instance = create(type);
    handlers.add(
        new HandlerCandidate(type.getSimpleName(), stage, stage.orderOf(instance), instance));
  }

  /** Nothing that cannot be created with a no-arg constructor of its own is a plugin or handler. */
  private static boolean isInstantiable(Class<?> type) {
    int modifiers = type.getModifiers();
    return !type.isInterface()
        && !Modifier.isAbstract(modifiers)
        && !type.isAnonymousClass()
        && !type.isLocalClass()
        && !(type.isMemberClass() && !Modifier.isStatic(modifiers));
  }

  private static Object create(Class<?> type) {
    try {
      Constructor<?> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException | LinkageError failure) {
      throw new IllegalStateException(
          type.getName() + " could not be created: " + describe(failure), failure);
    }
  }

  /** The class's own failure, not the reflection wrapped around it. */
  private static String describe(Throwable failure) {
    Throwable cause =
        failure instanceof InvocationTargetException
                || failure instanceof ExceptionInInitializerError
            ? Objects.requireNonNullElse(failure.getCause(), failure)
            : failure;
    return Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getName());
  }

  private static URL url(Path jar) {
    try {
      return jar.toUri().toURL();
    } catch (MalformedURLException e) {
      throw new UncheckedIOException(e);
    }
  }
}
