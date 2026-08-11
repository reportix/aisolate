package com.reportix.aisolate.toolserver.infrastructure;

import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.plugins.JarScan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The rule sets a developer ships next to the tools: every {@code .json} directly in {@code
 * aisolate/rules/}, one per entity. Read where the classpath loader or its parents answer {@code
 * getResources} for that directory (directories, and jars carrying a directory entry for it), and
 * in the given jars, scanned entry by entry so a jar tool that omits directory entries still works.
 * A file that is not a rule set, or one in a folder below that directory, fails the load naming the
 * file.
 */
public final class RuleSetFiles {

  static final String DIRECTORY = "aisolate/rules";

  public static List<RuleSet> discover(ClassLoader classpath, List<Path> jars) {
    // A jar reachable both ways is read once.
    Set<String> seen = new LinkedHashSet<>();
    List<RuleSet> found = new ArrayList<>();
    try {
      Enumeration<URL> roots = classpath.getResources(DIRECTORY);
      for (URL root : Collections.list(roots)) {
        if ("file".equals(root.getProtocol())) {
          Path directory = Path.of(root.toURI());
          try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
              String relative = directory.relativize(file).toString().replace('\\', '/');
              if (seen.add(root + "!" + relative)) {
                found.add(parse(relative, Files.readString(file)));
              }
            }
          }
        } else if ("jar".equals(root.getProtocol())) {
          // The JDK caches this JarFile for the URL: shared, so not ours to close.
          JarFile jar = ((JarURLConnection) root.openConnection()).getJarFile();
          found.addAll(scanJar(jar, seen));
        }
      }
      // Jars without a directory entry never answer getResources(DIRECTORY).
      for (Path path : jars) {
        try (JarFile jar = new JarFile(path.toFile())) {
          found.addAll(scanJar(jar, seen));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
    return List.copyOf(found);
  }

  private static List<RuleSet> scanJar(JarFile jar, Set<String> seen) throws IOException {
    List<RuleSet> found = new ArrayList<>();
    for (Map.Entry<String, String> file : JarScan.ruleFiles(jar).entrySet()) {
      String relative = file.getKey().substring(DIRECTORY.length() + 1);
      if (seen.add(jar.getName() + "!" + relative)) {
        found.add(parse(relative, file.getValue()));
      }
    }
    return found;
  }

  /**
   * @param relative the file's path under {@code aisolate/rules/}
   */
  private static RuleSet parse(String relative, String json) {
    String file = DIRECTORY + "/" + relative;
    JarScan.requireRuleSetPath(file);
    try {
      return RuleSet.parse(json);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(file + ": " + invalid.getMessage(), invalid);
    }
  }

  private RuleSetFiles() {}
}
