package com.reportix.aisolate.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The templates bundled in the binary, one directory each inside {@code templates.zip} (Java) and
 * {@code templates-python.zip}, and the SDK each language's project builds against.
 */
final class Templates {

  private static final Set<String> TEXT =
      Set.of(
          ".yaml",
          ".yml",
          ".kts",
          ".md",
          ".java",
          ".py",
          ".toml",
          ".json",
          ".properties",
          ".gitignore",
          ".txt",
          ".example");

  // Gradle's default excludes drop dot-files like .gitignore from the zip, so the template stores
  // them with a leading underscore.
  private static final String GITIGNORE = "_gitignore";

  /**
   * Extracts {@code <template>/**} of the build-generated {@code templates.zip} into {@code
   * target}, replacing each {@code ${NAME}} of {@code placeholders} ({@code SPACE}, {@code
   * AIRLOCK}, …) in files with a {@link #TEXT} extension and copying the rest as bytes.
   */
  static void extract(
      Language language, String template, Map<String, String> placeholders, Path target)
      throws IOException {
    extract(
        open(language == Language.python ? "templates-python.zip" : "templates.zip"),
        template,
        placeholders,
        target);
  }

  static void extract(
      InputStream templates, String template, Map<String, String> placeholders, Path target)
      throws IOException {
    String prefix = template + "/";
    boolean found = false;
    try (ZipInputStream zip = new ZipInputStream(templates)) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (!entry.getName().startsWith(prefix)) {
          continue;
        }
        found = true;
        Path file = target.resolve(entry.getName().substring(prefix.length())).normalize();
        if (!file.startsWith(target.normalize())) {
          throw new IOException("bad entry " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(file);
          continue;
        }
        if (file.getFileName().toString().equals(GITIGNORE)) {
          file = file.resolveSibling(".gitignore");
        }
        Files.createDirectories(file.getParent());
        byte[] bytes = zip.readAllBytes();
        if (isText(file)) {
          String text = new String(bytes, StandardCharsets.UTF_8);
          for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            text = text.replace("${" + placeholder.getKey() + "}", placeholder.getValue());
          }
          Files.writeString(file, text);
        } else {
          Files.write(file, bytes);
        }
        if (file.getFileName().toString().equals("gradlew")) {
          makeExecutable(file);
        }
      }
    }
    if (!found) {
      throw new IllegalArgumentException("unknown template " + template);
    }
  }

  /** Writes the bundled SDK jar to {@code libs/aisolate-sdk.jar}; refuses to overwrite one. */
  static void writeSdkJar(Path libs) throws IOException {
    Files.createDirectories(libs);
    try (InputStream in = open("sdk/aisolate-sdk.jar")) {
      Files.copy(in, libs.resolve("aisolate-sdk.jar"));
    }
  }

  /**
   * Writes the bundled Python SDK, its {@code pyproject.toml} and {@code src/aisolate}, to {@code
   * libs/aisolate-sdk}, where the project's {@code [tool.uv.sources]} points.
   */
  static void writePythonSdk(Path libs) throws IOException {
    extract(
        open("aisolate-sdk-python.zip"), "aisolate-sdk", Map.of(), libs.resolve("aisolate-sdk"));
  }

  private static InputStream open(String resource) {
    InputStream in = Templates.class.getClassLoader().getResourceAsStream(resource);
    if (in == null) {
      throw new IllegalStateException(resource + " is missing from this build");
    }
    return in;
  }

  private static boolean isText(Path file) {
    String name = file.getFileName().toString();
    return TEXT.stream().anyMatch(name::endsWith);
  }

  private static void makeExecutable(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (UnsupportedOperationException windows) {
      // No POSIX permissions: gradlew.bat is what runs there.
    }
  }

  private Templates() {}
}
