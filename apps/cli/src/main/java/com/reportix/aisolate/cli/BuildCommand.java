package com.reportix.aisolate.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code aisolate build}: a Java project's own Gradle wrapper builds its one jar, a Python
 * project's SDK its zip. No session.
 */
@Command(
    name = "build",
    mixinStandardHelpOptions = true,
    versionProvider = AIsolateCli.Version.class,
    description =
        "Build the project: a jar with its Gradle wrapper, or a Python project's zip with uv or"
            + " Python 3.13.")
@RequiredArgsConstructor
final class BuildCommand implements Callable<Integer> {

  private final Console console;

  @Mixin ProjectCommand project;

  /** The PATH uv and python3.13 are found on, replaceable so a test can set one. */
  String path = System.getenv("PATH");

  @Override
  public Integer call() {
    return ProjectCommand.run(
        console,
        () -> {
          ProjectFile.read(project.dir);
          build(console, project.dir, path);
          return 0;
        });
  }

  /** The build step, also {@code deploy}'s: the jar or zip it produced. */
  static Path build(Console console, Path project, String path) {
    Path dir = project.toAbsolutePath().normalize();
    console.info("Building " + dir.getFileName());
    Path artifact =
        Language.of(dir) == Language.python ? PythonBuild.build(dir, path) : GradleBuild.build(dir);
    console.success("Built " + dir.relativize(artifact));
    return artifact;
  }
}
