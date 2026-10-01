package com.reportix.aisolate.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The project's own Gradle wrapper builds its jar; the CLI never needs Gradle itself. */
final class GradleBuild {

  private static final List<String> NOT_THE_JAR =
      List.of("-plain.jar", "-sources.jar", "-javadoc.jar");

  // The templates name their jar after rootProject.name; no separator, so it stays in build/libs.
  private static final Pattern ROOT_NAME =
      Pattern.compile("rootProject\\.name\\s*=\\s*\"([\\w.-]+)\"");

  /**
   * Runs {@code gradlew build} in the project, its output streaming to the terminal, and returns
   * the jar named after the project, else the one jar in {@code build/libs}.
   */
  static Path build(Path projectDir) {
    Path dir = projectDir.toAbsolutePath().normalize();
    boolean windows = System.getProperty("os.name", "").startsWith("Windows");
    Path wrapper = dir.resolve(windows ? "gradlew.bat" : "gradlew");
    int exit;
    try {
      // Output to the terminal, plain console: the task lines and BUILD SUCCESSFUL are the progress
      // of a build that takes a minute. The child's stdin is a pipe closed at once, so a build that
      // reads stdin does not hang.
      Process process =
          new ProcessBuilder(wrapper.toString(), "build", "--console=plain")
              .directory(dir.toFile())
              .redirectOutput(ProcessBuilder.Redirect.INHERIT)
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      process.getOutputStream().close();
      exit = process.waitFor();
    } catch (IOException e) {
      throw new IllegalStateException("cannot run " + wrapper + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted");
    }
    if (exit != 0) {
      throw new IllegalStateException("the project build failed");
    }
    return jarIn(dir);
  }

  private static Path jarIn(Path dir) {
    Path libs = dir.resolve("build/libs");
    // A renamed project keeps the old name's jar in build/libs until a clean; its own wins.
    Path own = ownJar(dir, libs);
    if (own != null) {
      return own;
    }
    List<Path> jars;
    try (Stream<Path> files = Files.list(libs)) {
      jars =
          files
              .filter(file -> file.getFileName().toString().endsWith(".jar"))
              .filter(
                  file -> NOT_THE_JAR.stream().noneMatch(file.getFileName().toString()::endsWith))
              .sorted()
              .toList();
    } catch (NoSuchFileException none) {
      jars = List.of();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (jars.isEmpty()) {
      throw new IllegalStateException("no jar in build/libs");
    }
    if (jars.size() > 1) {
      throw new IllegalStateException(
          "several jars in build/libs: "
              + jars.stream()
                  .map(jar -> jar.getFileName().toString())
                  .collect(Collectors.joining(", "))
              + "; keep one");
    }
    return jars.getFirst();
  }

  /** {@code build/libs/<rootProject.name>.jar} when settings.gradle.kts names one that exists. */
  private static Path ownJar(Path dir, Path libs) {
    String settings;
    try {
      settings = Files.readString(dir.resolve("settings.gradle.kts"));
    } catch (NoSuchFileException none) {
      return null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Matcher name = ROOT_NAME.matcher(settings);
    if (!name.find()) {
      return null;
    }
    Path jar = libs.resolve(name.group(1) + ".jar");
    return Files.isRegularFile(jar) ? jar : null;
  }

  private GradleBuild() {}
}
