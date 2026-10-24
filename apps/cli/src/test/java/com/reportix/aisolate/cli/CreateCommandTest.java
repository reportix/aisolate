package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class CreateCommandTest {

  private static final boolean POSIX =
      FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

  @TempDir Path parent;

  private final FakeConsole console = new FakeConsole();
  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  private CommandLine cli() {
    CommandLine cli = AIsolateCli.commandLine(console);
    cli.setOut(new PrintWriter(out, true));
    cli.setErr(new PrintWriter(err, true));
    return cli;
  }

  @Test
  void givenNoLanguage_whenCreate_thenAPythonProject() throws Exception {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit = cli.execute("create", "-s=hr-lab", "-t=company-hr", "-p=" + parent);

    // Assert
    assertThat(exit).isZero();
    Path project = parent.resolve("company-hr");
    assertThat(project.resolve("pyproject.toml")).exists();
    assertThat(project.resolve("src/acme_hr/__init__.py")).exists();
    assertThat(project.resolve("settings.gradle.kts")).doesNotExist();
  }

  @Test
  void givenJavaLanguage_whenCreate_thenTheGradleTreeWithTheSpaceFilledIn() throws Exception {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit =
        cli.execute("create", "-s=hr-lab", "-t=company-hr", "--language=java", "-p=" + parent);

    // Assert
    assertThat(exit).isZero();
    Path project = parent.resolve("company-hr");
    assertThat(Files.readString(project.resolve(".env")))
        .contains("AISOLATE_SPACE=hr-lab\n")
        .doesNotContain("${SPACE}");
    assertThat(Files.readString(project.resolve("settings.gradle.kts")))
        .contains("rootProject.name = \"company-hr\"");
    assertThat(project.resolve(".gitignore")).exists();
    assertThat(project.resolve("_gitignore")).doesNotExist();
    assertThat(project.resolve("gradle/wrapper/gradle-wrapper.jar"))
        .hasSameBinaryContentAs(
            Path.of("src/main/templates/company-hr/gradle/wrapper/gradle-wrapper.jar"));
    assertThat(project.resolve("libs/aisolate-sdk.jar")).exists();
    assertThat(Files.isExecutable(project.resolve("gradlew"))).isTrue();
    assertThat(console.lines)
        .containsExactly(
            "Created " + project + " from the company-hr template.",
            "Next: cd "
                + project
                + ", then aisolate login and aisolate deploy (the space exists in the AIsolate UI"
                + " first; the deploy creates or updates the airlock)");
  }

  @ParameterizedTest
  @CsvSource({
    "company-hr, hr-contracts, acme_hr",
    "company-sales, sales, acme_sales",
    "auditor, northbeck, halvardfinch_audit",
    "hospital, cardiology, aldermoor_ehr"
  })
  void givenPythonLanguage_whenCreate_thenThePythonTreeWithItsSdkAndNoPlaceholderLeft(
      String template, String airlock, String pkg) throws Exception {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit =
        cli.execute(
            "create",
            "-s=hr-lab",
            "-t=" + template,
            "--airlock=" + airlock,
            "--language=python",
            "-p=" + parent);

    // Assert
    assertThat(exit).isZero();
    Path project = parent.resolve(template + "-" + airlock);
    assertThat(project.resolve("pyproject.toml")).exists();
    assertThat(project.resolve("src/" + pkg + "/__init__.py")).exists();
    assertThat(project.resolve("libs/aisolate-sdk/pyproject.toml")).exists();
    assertThat(project.resolve("libs/aisolate-sdk/src/aisolate/build.py")).exists();
    assertThat(project.resolve(".gitignore")).exists();
    assertThat(project.resolve("_gitignore")).doesNotExist();
    assertThat(project.resolve("settings.gradle.kts")).doesNotExist();
    assertThat(Files.readString(project.resolve(".env"))).contains("AISOLATE_SPACE=hr-lab\n");
    try (Stream<Path> files = Files.walk(project)) {
      assertThat(files.filter(Files::isRegularFile))
          .allSatisfy(
              file -> {
                assertThat(file.toString()).doesNotContain("__pycache__", ".pytest_cache");
                assertThat(Files.readString(file)).doesNotContain("${SPACE}", "${AIRLOCK");
              });
    }
  }

  @ParameterizedTest
  @CsvSource({"company-hr, hr-contracts", "company-sales, sales", "hospital, cardiology"})
  void givenNoAirlock_whenCreate_thenTheTemplatesAirlockInItsOwnDirectory(
      String template, String airlock) throws Exception {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit = cli.execute("create", "-s=lab", "-t=" + template, "-p=" + parent);

    // Assert
    assertThat(exit).isZero();
    assertThat(Files.readString(parent.resolve(template).resolve("aisolate.yaml")))
        .contains("kind: Airlock\nmetadata:\n  name: " + airlock + "\n")
        .doesNotContain("${AIRLOCK}", "${SPACE}", "${AIRLOCK_", "---");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "auditor|northbeck|Northbeck Logistics GmbH|'  # No roles of its own'",
        "auditor|harwick|Harwick|'  # No roles of its own'",
        "hospital|cardiology|Cardiology|'  access: {roles: [doctor]}'",
        "hospital|psychiatry|Psychiatry|'  access: {roles: [doctor]}'",
        "hospital|neurology|Neurology|'  # access: {roles: [...]}'"
      })
  void givenParameterisedTemplate_whenCreate_thenItsAirlockDocumentNamesTheAirlockAndItsRoles(
      String template, String airlock, String name, String access) throws Exception {
    // Arrange: an airlock the templates do not know is named after its id
    CommandLine cli = cli();

    // Act
    int exit =
        cli.execute("create", "-s=lab", "-t=" + template, "--airlock=" + airlock, "-p=" + parent);

    // Assert
    assertThat(exit).isZero();
    assertThat(Files.readString(parent.resolve(template + "-" + airlock).resolve("aisolate.yaml")))
        .contains(
            "kind: Airlock\nmetadata:\n  name: " + airlock + "\n",
            "  displayName: " + name + "\n",
            "  description: \"" + name + ": ",
            access)
        .doesNotContain("${");
  }

  @ParameterizedTest
  @CsvSource({"auditor, northbeck", "hospital, psychiatry", "company-hr, people"})
  void givenAirlock_whenCreate_thenItsIdInADirectoryOfTemplateAndAirlock(
      String template, String airlock) throws Exception {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit =
        cli.execute("create", "-s=lab", "-t=" + template, "--airlock=" + airlock, "-p=" + parent);

    // Assert
    assertThat(exit).isZero();
    Path project = parent.resolve(template + "-" + airlock);
    assertThat(Files.readString(project.resolve("aisolate.yaml")))
        .contains("kind: Airlock\nmetadata:\n  name: " + airlock + "\n")
        .doesNotContain("${AIRLOCK}", "---");
    assertThat(parent.resolve(template)).doesNotExist();
    assertThat(console.lines)
        .first()
        .isEqualTo("Created " + project + " from the " + template + " template.");
  }

  @Test
  void givenAuditorWithoutAirlock_whenCreate_thenOneLineAndExit2() {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit = cli.execute("create", "-s=lab", "-t=auditor", "-p=" + parent);

    // Assert
    assertThat(exit).isEqualTo(2);
    assertThat(console.errors)
        .containsExactly("the auditor template needs --airlock=<company>: one airlock per company");
    assertThat(parent).isEmptyDirectory();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Cardiology", "../x", "a", "cardiology-"})
  void givenAirlockTheServerRefuses_whenCreate_thenOneLineAndExit2(String airlock) {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit = cli.execute("create", "-s=lab", "-t=hospital", "-a=" + airlock, "-p=" + parent);

    // Assert
    assertThat(exit).isEqualTo(2);
    assertThat(console.errors)
        .containsExactly(
            "airlock must be 2 to 41 lowercase letters, digits or hyphens, starting with a letter"
                + " and not ending with a hyphen");
    assertThat(parent).isEmptyDirectory();
  }

  @Test
  void givenHrTemplate_whenCreate_thenEnvFileFromTheExampleOwnerOnlyAndIgnored() throws Exception {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit = cli.execute("create", "-s=hr-lab", "-t=company-hr", "-p=" + parent);

    // Assert
    assertThat(exit).isZero();
    Path project = parent.resolve("company-hr");
    assertThat(project.resolve(".env")).hasSameTextualContentAs(project.resolve(".env.example"));
    assertThat(Files.readString(project.resolve(".env.example")))
        .contains("AISOLATE_ENDPOINT=http://localhost:8080\n")
        .contains("AISOLATE_SPACE=hr-lab\n")
        .doesNotContain("HR_USERNAME", "HR_PASSWORD", "LLM_URL", "HR_DOCUMENTS");
    if (POSIX) {
      assertThat(
              PosixFilePermissions.toString(Files.getPosixFilePermissions(project.resolve(".env"))))
          .isEqualTo("rw-------");
    }
    assertThat(Files.readAllLines(project.resolve(".gitignore"))).contains(".env");
  }

  @Test
  void givenTemplateWithItsOwnEnvFile_whenCreate_thenItsEnvFileKept() throws Exception {
    // Arrange: the copy from the example is for templates without one
    CreateCommand create = new CreateCommand(console);
    create.scaffold =
        (template, space, airlock, project) -> {
          Files.createDirectories(project);
          Files.writeString(project.resolve(".env.example"), "from the example");
          Files.writeString(project.resolve(".env"), "brought by the template");
        };

    // Act
    int exit = new CommandLine(create).execute("-s=hr-lab", "-t=company-hr", "-p=" + parent);

    // Assert
    assertThat(exit).isZero();
    assertThat(parent.resolve("company-hr/.env")).hasContent("brought by the template");
  }

  @ParameterizedTest
  @ValueSource(strings = {"a non-empty directory", "a dangling symlink"})
  void givenProjectPathTaken_whenCreate_thenRefusedAndLeftAlone(String taken) throws Exception {
    // Arrange
    Path existing = parent.resolve("company-hr");
    if (taken.equals("a dangling symlink")) {
      Files.createSymbolicLink(existing, parent.resolve("nowhere"));
    } else {
      Files.writeString(Files.createDirectories(existing).resolve("aisolate.yaml"), "kind: Space");
    }
    CommandLine cli = cli();

    // Act
    int exit = cli.execute("create", "-s=hr-lab", "-t=company-hr", "-p=" + parent);

    // Assert: nothing written through it
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).containsExactly(existing + " already exists");
    assertThat(existing.resolve("gradlew")).doesNotExist();
    assertThat(parent.resolve("nowhere")).doesNotExist();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../x",
        "1",
        "a",
        "1hr",
        "hr-",
        "Hr",
        "hr_lab",
        "abcdefghijklmnopqrstuvwxyzabcdefghijklmnop"
      })
  void givenSpaceTheServerRefuses_whenCreate_thenOneLineAndExit2(String space) {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit = cli.execute("create", "-s=" + space, "-t=company-hr", "-p=" + parent);

    // Assert
    assertThat(exit).isEqualTo(2);
    assertThat(console.errors)
        .containsExactly(
            "space must be 2 to 41 lowercase letters, digits or hyphens, starting with a letter"
                + " and not ending with a hyphen");
    assertThat(parent).isEmptyDirectory();
  }

  @Test
  void givenExtractionFailsPartway_whenCreate_thenHalfWrittenProjectRemovedAndRerunWorks()
      throws Exception {
    // Arrange
    CreateCommand failing = new CreateCommand(console);
    failing.scaffold =
        (template, space, airlock, project) -> {
          Files.createDirectories(project.resolve("src"));
          Files.writeString(project.resolve("src/A.java"), "x");
          throw new IOException("disk full");
        };
    CommandLine cli = new CommandLine(failing);

    // Act
    int exit = cli.execute("-s=hr-lab", "-t=company-hr", "-p=" + parent);
    int rerun =
        AIsolateCli.commandLine(console)
            .execute("create", "-s=hr-lab", "-t=company-hr", "-p=" + parent);

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors)
        .containsExactly("cannot create " + parent.resolve("company-hr") + ": disk full");
    assertThat(rerun).isZero();
  }

  @Test
  void givenFileSystemException_whenCreate_thenReasonAppearsOnce() {
    // Arrange
    CreateCommand failing = new CreateCommand(console);
    failing.scaffold =
        (template, space, airlock, project) -> {
          throw new FileSystemException(project + "/x", null, "Not a directory");
        };
    CommandLine cli = new CommandLine(failing);

    // Act
    int exit = cli.execute("-s=hr-lab", "-t=company-hr", "-p=" + parent);

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors)
        .containsExactly("cannot create " + parent.resolve("company-hr") + ": Not a directory");
  }

  @ParameterizedTest
  @ValueSource(strings = {"create", "login", "logout", "build", "deploy"})
  void givenSubcommand_whenVersionThenHelp_thenTheRootsVersionLineThenItsUsage(String command) {
    // Arrange
    CommandLine cli = cli();
    String[] words = command.split(" ");

    // Act
    int versionExit = cli.execute(append(words, "--version"));
    String version = out.toString();
    out.getBuffer().setLength(0);
    int helpExit = cli.execute(append(words, "--help"));

    // Assert
    assertThat(versionExit).isZero();
    assertThat(version).isEqualTo("aisolate " + VersionConstants.VERSION + "\n");
    assertThat(helpExit).isZero();
    assertThat(out.toString()).startsWith("Usage: aisolate " + command);
  }

  @Test
  void givenUnknownTemplate_whenCreate_thenUsageError() {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit = cli.execute("create", "-s=hr-lab", "-t=finance", "-p=" + parent);

    // Assert
    assertThat(exit).isEqualTo(2);
    assertThat(err.toString()).contains("company-hr", "company-sales", "auditor", "hospital");
    assertThat(parent).isEmptyDirectory();
  }

  @Test
  void givenHelp_whenRun_thenUsageListsTheDeveloperCommands() {
    // Arrange
    CommandLine cli = cli();

    // Act
    int exit = cli.execute("--help");

    // Assert
    assertThat(exit).isZero();
    assertThat(out.toString())
        .contains("create")
        .contains("login")
        .contains("logout")
        .contains("build")
        .contains("deploy")
        .doesNotContain("validate", "export");
  }

  private static String[] append(String[] words, String option) {
    String[] args = Arrays.copyOf(words, words.length + 1);
    args[words.length] = option;
    return args;
  }
}
