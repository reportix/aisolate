package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

/** {@code build} with a {@code gradlew} script standing in for Gradle; no session, no network. */
class BuildCommandTest {

  @TempDir Path tmp;

  private final FakeConsole console = new FakeConsole();
  private Path project;

  @BeforeEach
  void project() throws IOException {
    project = TestProject.create(tmp.resolve("hr-lab"), "hr-lab", "http://localhost:8080");
  }

  @Test
  void givenProject_whenBuild_thenBuildingAndBuiltLines() throws Exception {
    // Arrange
    TestProject.gradlew(project, "mkdir -p build/libs && touch build/libs/hr-lab.jar");

    // Act
    int exit = build();

    // Assert
    assertThat(exit).isZero();
    assertThat(console.lines).containsExactly("⏺ Building hr-lab", "✔ Built build/libs/hr-lab.jar");
    assertThat(console.errors).isEmpty();
  }

  @Test
  void givenStaleJarOfTheOldName_whenBuild_thenTheJarNamedInSettings() throws Exception {
    // Arrange
    Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"hr-lab\"\n");
    TestProject.gradlew(
        project,
        "mkdir -p build/libs && touch build/libs/company-resources.jar build/libs/hr-lab.jar");

    // Act
    int exit = build();

    // Assert
    assertThat(exit).isZero();
    assertThat(console.lines).containsExactly("⏺ Building hr-lab", "✔ Built build/libs/hr-lab.jar");
    assertThat(console.errors).isEmpty();
  }

  @Test
  void givenFailingBuild_whenBuild_thenOneLineAndExit1() throws Exception {
    // Arrange
    TestProject.gradlew(project, "exit 3");

    // Act
    int exit = build();

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).containsExactly("the project build failed");
  }

  @ParameterizedTest
  @CsvSource({"pyproject.toml, python", "settings.gradle.kts, java", "'', java"})
  void givenAProjectFile_whenItsLanguageIsRead_thenPythonOnlyWithAPyproject(
      String file, Language expected) throws IOException {
    // Arrange
    if (!file.isEmpty()) {
      Files.writeString(project.resolve(file), "");
    }

    // Act
    Language language = Language.of(project);

    // Assert
    assertThat(language).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"'uv,python3.13', uv", "python3.13, python3.13"})
  void givenPythonProjectAndTools_whenBuild_thenUvFirstAndTheZipNamedAfterThePackage(
      String tools, String used) throws Exception {
    // Arrange: each tool records its arguments and writes the --out file, as the SDK's build does
    Files.writeString(project.resolve("pyproject.toml"), "[project]\nname = \"hr-lab\"\n");
    Files.createDirectories(project.resolve("src/acme_hr"));
    Files.writeString(project.resolve("src/acme_hr/__init__.py"), "");
    Path bin = Files.createDirectories(tmp.resolve("bin"));
    for (String tool : tools.split(",")) {
      TestProject.script(
          bin.resolve(tool),
          "echo \""
              + tool
              + " $*\" > \""
              + tmp.resolve("ran")
              + "\"\n"
              + "for a; do out=$a; done; mkdir -p \"$(dirname \"$out\")\" && touch \"$out\"");
    }

    // Act
    int exit = build(bin.toString());

    // Assert
    assertThat(exit).isZero();
    assertThat(console.lines)
        .containsExactly("⏺ Building hr-lab", "✔ Built build/aisolate/acme_hr.zip");
    assertThat(Files.readString(tmp.resolve("ran")))
        .startsWith(used + " ")
        .contains("-m aisolate build " + project.toAbsolutePath().normalize())
        .endsWith(
            "--out "
                + project.toAbsolutePath().normalize().resolve("build/aisolate/acme_hr.zip")
                + "\n");
  }

  @Test
  void givenPythonProjectWithoutUvOrPython313_whenBuild_thenOneLineNamingBoth() throws Exception {
    // Arrange
    Files.writeString(project.resolve("pyproject.toml"), "");

    // Act
    int exit = build(Files.createDirectories(tmp.resolve("empty-bin")).toString());

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).containsExactly(PythonBuild.NO_PYTHON);
  }

  private int build() {
    return new CommandLine(new BuildCommand(console)).execute("-p=" + project);
  }

  private int build(String path) {
    BuildCommand command = new BuildCommand(console);
    command.path = path;
    return new CommandLine(command).execute("-p=" + project);
  }
}
