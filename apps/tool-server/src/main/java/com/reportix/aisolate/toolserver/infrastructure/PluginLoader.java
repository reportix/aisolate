package com.reportix.aisolate.toolserver.infrastructure;

import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.plugins.JarDigests;
import com.reportix.aisolate.plugins.JarScan;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the plugins on the classpath (a developer's and the tests' convenience, through {@code
 * META-INF/services}) plus the ones every pinned jar of the plugins directory holds, found by
 * scanning the jar's classes, with the rule sets they carry. A pinned jar's services entry is
 * ignored. Parent-first, so a plugin sees this server's SDK classes; the loader is never closed,
 * the instances live as long as the catalog does.
 */
@Slf4j
public final class PluginLoader {

  public record Loaded(List<ToolPlugin> plugins, List<RuleSet> ruleSets) {}

  /**
   * @throws IllegalStateException a jar of the directory is not one of {@code pinned}, or the scan
   *     refuses one of its classes
   */
  public static Loaded load(String pluginsDir, String digests) {
    ClassLoader loader = ToolPlugin.class.getClassLoader();
    List<ToolPlugin> plugins =
        new ArrayList<>(
            ServiceLoader.load(ToolPlugin.class, loader).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    List<Path> jars = List.copyOf(JarDigests.pinnedJars(pluginsDir, digests).values());
    if (!pluginsDir.isBlank()) {
      log.info("loading {} plugin jar(s) from {}", jars.size(), pluginsDir);
      plugins.addAll(scan(jars).plugins());
    }
    return new Loaded(List.copyOf(plugins), RuleSetFiles.discover(loader, jars));
  }

  private static JarScan scan(List<Path> jars) {
    try {
      return JarScan.of(jars);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private PluginLoader() {}
}
