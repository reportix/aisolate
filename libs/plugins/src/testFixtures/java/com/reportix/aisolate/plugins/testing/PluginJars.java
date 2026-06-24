package com.reportix.aisolate.plugins.testing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * Tool plugin jars as a customer's build packages them: the plugin compiled at test time into a
 * package on no classpath, so a loader can load it from the jar only. Any package; no services
 * entry.
 */
public final class PluginJars {

  /** A plugin's source; its class name tells javac where the file sits. */
  public record Plugin(String className, String source) {}

  /** The demo HR plugin's two tools. */
  public static final Plugin HR_TOOLS =
      new Plugin(
          "com.example.hr.HrTools",
          """
          package com.example.hr;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;
          import java.util.Map;

          public class HrTools implements ToolPlugin {
            public String name() {
              return "hr-tools";
            }

            public String dataSource() {
              return "acme_hr";
            }

            public List<Entity> entities() {
              return List.of();
            }

            public List<Tool> tools() {
              return List.of(
                  new Tool("read_records", "Reads the rows of an entity the user may see.",
                      Map.of(), null),
                  new Tool("aggregate_records", "Aggregates one column of an entity.",
                      Map.of(), null));
            }
          }
          """);

  /** A stricter read_records, and one more tool. */
  public static final Plugin STRICT_HR_TOOLS =
      new Plugin(
          "com.example.hr.StrictTools",
          """
          package com.example.hr;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;
          import java.util.Map;

          public class StrictTools implements ToolPlugin {
            public String name() {
              return "strict-hr-tools";
            }

            public String dataSource() {
              return "acme_hr";
            }

            public List<Entity> entities() {
              return List.of();
            }

            public List<Tool> tools() {
              return List.of(
                  new Tool("read_records", "Strict: at most one record.", Map.of(), null),
                  new Tool("export_payslips", "Exports the user's payslips.", Map.of(), null));
            }
          }
          """);

  /** Deep in a package with airlocks and versions segments, which mean nothing: it loads. */
  public static final Plugin DEEP_TOOLS =
      new Plugin(
          "com.example.hr.deep.airlocks.x.versions.v2.DeepTools",
          """
          package com.example.hr.deep.airlocks.x.versions.v2;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;
          import java.util.Map;

          public class DeepTools implements ToolPlugin {
            public String name() {
              return "deep-tools";
            }

            public String dataSource() {
              return "acme_hr";
            }

            public List<Entity> entities() {
              return List.of();
            }

            public List<Tool> tools() {
              return List.of(
                  new Tool("read_records", "Deep: at most 20 records.", Map.of(), null));
            }
          }
          """);

  /** One tool, for the jar that replaces another. */
  public static final Plugin PAYROLL_TOOLS =
      new Plugin(
          "com.example.payroll.PayrollTools",
          """
          package com.example.payroll;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;
          import java.util.Map;

          public class PayrollTools implements ToolPlugin {
            public String name() {
              return "payroll-tools";
            }

            public String dataSource() {
              return "acme_hr";
            }

            public List<Entity> entities() {
              return List.of();
            }

            public List<Tool> tools() {
              return List.of(
                  new Tool("export_payslips", "Exports the user's payslips.", Map.of(), null));
            }
          }
          """);

  /** Fails where a plugin missing its environment does: in its constructor. */
  public static final Plugin BROKEN_TOOLS =
      new Plugin(
          "com.example.broken.BrokenTools",
          """
          package com.example.broken;

          import com.reportix.aisolate.sdk.*;
          import java.util.List;

          public class BrokenTools implements ToolPlugin {
            public BrokenTools() {
              throw new IllegalStateException("no database");
            }

            public String name() {
              return "broken-tools";
            }

            public String dataSource() {
              return "acme_hr";
            }

            public List<Entity> entities() {
              return List.of();
            }

            public List<Tool> tools() {
              return List.of();
            }
          }
          """);

  /** A jar of the plugin's classes. */
  public static byte[] buildPluginJar(Plugin plugin) {
    return buildJar(compile(plugin));
  }

  /** Every class file javac produces for these sources, compiled together, by path in a jar. */
  public static Map<String, byte[]> compile(Plugin... plugins) {
    try {
      Path dir = Files.createTempDirectory("plugin-");
      List<String> args =
          new ArrayList<>(
              List.of(
                  "-proc:none",
                  "-classpath",
                  System.getProperty("java.class.path"),
                  "-d",
                  dir.toString()));
      for (Plugin plugin : plugins) {
        Path source = dir.resolve(plugin.className().replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, plugin.source());
        args.add(source.toString());
      }
      int exit =
          ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
      Map<String, byte[]> classes = new TreeMap<>();
      try (Stream<Path> files = Files.walk(dir)) {
        // Deepest first, so each directory is empty by the time it is deleted.
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
          if (file.toString().endsWith(".class")) {
            classes.put(
                dir.relativize(file).toString().replace(File.separatorChar, '/'),
                Files.readAllBytes(file));
          }
          Files.delete(file);
        }
      }
      if (exit != 0) {
        throw new IllegalStateException(plugins[0].className() + " does not compile");
      }
      return classes;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes exactly these entries (a name ending in {@code /} is a directory entry). */
  public static void writeJar(Path file, Map<String, byte[]> entries) throws IOException {
    Files.write(file, buildJar(new TreeMap<>(entries)));
  }

  /** A jar holding exactly these entries. */
  public static byte[] buildJar(Map<String, byte[]> entries) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (JarOutputStream jar = new JarOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        jar.putNextEntry(new JarEntry(entry.getKey()));
        jar.write(entry.getValue());
        jar.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /** Every entry of a jar by name, the inverse of {@link #buildJar}. */
  public static Map<String, byte[]> entriesOf(byte[] jar) {
    Map<String, byte[]> entries = new TreeMap<>();
    try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
      for (JarEntry entry = in.getNextJarEntry(); entry != null; entry = in.getNextJarEntry()) {
        entries.put(entry.getName(), in.readAllBytes());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return entries;
  }

  private PluginJars() {}
}
