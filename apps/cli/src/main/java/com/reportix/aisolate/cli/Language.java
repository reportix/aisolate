package com.reportix.aisolate.cli;

import java.nio.file.Files;
import java.nio.file.Path;

/** The language of a project's code: the templates {@code create} writes, the build it runs. */
enum Language {
  java,
  python;

  /** A project with a {@code pyproject.toml} is Python; any other is a Gradle project. */
  static Language of(Path project) {
    return Files.isRegularFile(project.resolve("pyproject.toml")) ? python : java;
  }
}
