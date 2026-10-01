package com.reportix.aisolate.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A Python project's artifact, written by the SDK the project carries ({@code python -m aisolate
 * build}): it imports the project's package on this machine to write the manifest, so the backend
 * never has to. uv runs it when it is on the PATH, else Python 3.13 with the SDK on its path.
 */
final class PythonBuild {

  static final String NO_PYTHON =
      "a Python project builds with uv (https://docs.astral.sh/uv/) or Python 3.13: install one,"
          + " so that uv or python3.13 is on the PATH";

  /**
   * Runs the build in the project, its output streaming to the terminal, and returns the zip.
   *
   * @param path the PATH uv and python3.13 are looked up on
   */
  static Path build(Path dir, String path) {
    Path artifact = dir.resolve("build/aisolate/" + packageName(dir) + ".zip");
    ProcessBuilder builder =
        new ProcessBuilder(command(dir, artifact, path)).directory(dir.toFile());
    // The SDK has no dependencies: without uv, its source on the path is the whole install.
    builder.environment().put("PYTHONPATH", dir.resolve("libs/aisolate-sdk/src").toString());
    int exit;
    try {
      Process process =
          builder
              .redirectOutput(ProcessBuilder.Redirect.INHERIT)
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      process.getOutputStream().close();
      exit = process.waitFor();
    } catch (IOException e) {
      throw new IllegalStateException(
          "cannot run " + builder.command().getFirst() + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted");
    }
    if (exit != 0 || !Files.isRegularFile(artifact)) {
      throw new IllegalStateException("the project build failed");
    }
    return artifact;
  }

  /** uv first: it brings Python 3.13 and the project's dependencies; else python3.13 itself. */
  static List<String> command(Path dir, Path artifact, String path) {
    List<String> build =
        List.of("-m", "aisolate", "build", dir.toString(), "--out", artifact.toString());
    List<String> command = new ArrayList<>();
    Path uv = onPath("uv", path);
    Path python = uv == null ? onPath("python3.13", path) : null;
    if (uv != null) {
      command.addAll(
          List.of(uv.toString(), "run", "--project", dir.toString(), "--no-dev", "python"));
    } else if (python != null) {
      command.add(python.toString());
    } else {
      throw new IllegalStateException(NO_PYTHON);
    }
    command.addAll(build);
    return command;
  }

  /**
   * The one package under {@code src/}, which names the artifact as the SDK's own default does; the
   * build itself refuses anything but one.
   */
  private static String packageName(Path dir) {
    try (Stream<Path> entries = Files.list(dir.resolve("src"))) {
      List<Path> packages =
          entries.filter(p -> Files.isRegularFile(p.resolve("__init__.py"))).toList();
      return packages.size() == 1 ? packages.getFirst().getFileName().toString() : "artifact";
    } catch (IOException noSrc) {
      return "artifact";
    }
  }

  private static Path onPath(String name, String path) {
    if (path == null) {
      return null;
    }
    for (String entry : path.split(File.pathSeparator)) {
      // Windows may quote an entry with spaces; the quotes are no part of the path.
      String dir = entry.replace("\"", "");
      for (String suffix : List.of("", ".exe")) {
        Path candidate = Path.of(dir.isEmpty() ? "." : dir, name + suffix);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
          return candidate;
        }
      }
    }
    return null;
  }

  private PythonBuild() {}
}
