package com.reportix.aisolate.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * A project directory reduced to what the commands read: its Airlock and {@code .env}; and the
 * shell scripts that stand in for its Gradle wrapper and for uv or Python.
 */
final class TestProject {

  static final String AIRLOCK =
      """
      # The airlock this project deploys.
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata:
        name: hr-assistant
      spec:
        displayName: HR assistant
      """;

  private TestProject() {}

  static Path create(Path dir, String space, String endpoint) throws IOException {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(ProjectFile.FILE), AIRLOCK);
    Files.writeString(
        dir.resolve(ProjectFile.ENV_FILE),
        "AISOLATE_ENDPOINT=" + endpoint + "\nAISOLATE_SPACE=" + space + "\n");
    return dir;
  }

  /** The project's {@code gradlew} as a shell script: no real Gradle build runs. */
  static void gradlew(Path project, String script) throws IOException {
    script(project.resolve("gradlew"), script);
  }

  static void script(Path file, String body) throws IOException {
    Files.writeString(file, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}
