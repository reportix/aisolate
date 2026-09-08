package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code aisolate.yaml} and {@code .env}: the space and the endpoint from the environment. */
class ProjectFileTest {

  @TempDir Path project;

  @Test
  void givenSpaceEmptyInTheShell_whenRead_thenTheEnvFiles() throws Exception {
    // Arrange: the space reads like the endpoint, whose order the endpoint test pins
    Files.writeString(project.resolve(ProjectFile.FILE), TestProject.AIRLOCK);
    Files.writeString(project.resolve(".env"), "AISOLATE_SPACE=hr-file\n");

    // Act
    ProjectFile config = ProjectFile.read(project, Map.of("AISOLATE_SPACE", ""));

    // Assert
    assertThat(config.space()).isEqualTo("hr-file");
  }

  @Test
  void givenNoSpaceVariable_whenRead_thenRefusedNamingIt() throws Exception {
    // Arrange: an empty shell value falls through to .env, which does not exist
    Files.writeString(project.resolve(ProjectFile.FILE), TestProject.AIRLOCK);

    // Act
    Throwable thrown =
        catchThrowable(() -> ProjectFile.read(project, Map.of("AISOLATE_SPACE", "")));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("AISOLATE_SPACE is not set: add AISOLATE_SPACE=<space> to .env");
  }

  @ParameterizedTest
  @ValueSource(strings = {"../x", "a?b", "hr/../../admin", "hr-", "x"})
  void givenSpaceTheServerRefuses_whenRead_thenRefusedBeforeItReachesAUrl(String space)
      throws Exception {
    // Arrange
    Files.writeString(project.resolve(ProjectFile.FILE), TestProject.AIRLOCK);

    // Act
    Throwable thrown =
        catchThrowable(() -> ProjectFile.read(project, Map.of("AISOLATE_SPACE", space)));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "AISOLATE_SPACE must be 2 to 41 lowercase letters, digits or hyphens, starting with a"
                + " letter and not ending with a hyphen");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      nullValues = "unset",
      textBlock =
          """
          # shell          | .env             | endpoint
          https://shell/   | https://file     | https://shell
          ''               | https://file///  | https://file
          unset            | unset            | http://localhost:8080
          """)
  void givenEndpointInTheShellOrTheEnvFile_whenRead_thenShellThenEnvFileThenDefault(
      String shell, String envFile, String expected) throws Exception {
    // Arrange
    Files.writeString(project.resolve(ProjectFile.FILE), TestProject.AIRLOCK);
    if (envFile != null) {
      Files.writeString(project.resolve(".env"), "AISOLATE_ENDPOINT=" + envFile + "\n");
    }
    Map<String, String> env = new HashMap<>(Map.of("AISOLATE_SPACE", "hr-lab"));
    if (shell != null) {
      env.put("AISOLATE_ENDPOINT", shell);
    }

    // Act
    ProjectFile config = ProjectFile.read(project, env);

    // Assert
    assertThat(config.endpoint()).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # endpoint                    | refused
          http://aisolate.corp.example  | true
          http://10.0.0.5:8080          | true
          HTTP://localhost.example.com  | true
          https://aisolate.corp.example | false
          http://localhost:8080         | false
          http://127.0.0.1:8080         | false
          http://[::1]:8080             | false
          http://aisolate.localhost     | false
          """)
  void givenEndpoint_whenRead_thenPlainHttpRefusedUnlessLocal(String endpoint, boolean refused)
      throws Exception {
    // Arrange
    Files.writeString(project.resolve(ProjectFile.FILE), TestProject.AIRLOCK);
    Map<String, String> env = Map.of("AISOLATE_SPACE", "hr-lab", "AISOLATE_ENDPOINT", endpoint);

    // Act
    Throwable thrown = catchThrowable(() -> ProjectFile.read(project, env));

    // Assert
    if (refused) {
      assertThat(thrown)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "refusing AISOLATE_ENDPOINT="
                  + endpoint
                  + ": tokens and passwords would travel unencrypted; use https (plain http is"
                  + " for localhost only)");
    } else {
      assertThat(thrown).isNull();
    }
  }

  @Test
  void givenNoProjectFile_whenRead_thenNamesTheDirectory() {
    // Arrange
    Path empty = project.resolve("empty");

    // Act
    Throwable thrown = catchThrowable(() -> ProjectFile.read(empty, Map.of()));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "no aisolate.yaml in "
                + empty.toAbsolutePath().normalize()
                + ": run this in a project created by aisolate create");
  }

  @Test
  void givenEnvFileLines_whenReadEnvFile_thenOneValuePerKeyAsWritten() throws Exception {
    // Arrange
    Path envFile =
        Files.writeString(
            project.resolve(".env"),
            """
            # COMMENTED=out
            PLAIN=reader
              export EXPORTED = exported
            DOUBLE="p#ss: 'word'"
            SINGLE=' se cret '
            EMPTY=
            INLINE=value # part of the value
            not a variable
            =no name
            """);

    // Act
    Map<String, String> values = ProjectFile.readEnvFile(envFile);

    // Assert
    assertThat(values)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "PLAIN", "reader",
                "EXPORTED", "exported",
                "DOUBLE", "p#ss: 'word'",
                "SINGLE", " se cret ",
                "EMPTY", "",
                "INLINE", "value # part of the value"));
  }
}
