package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole binary as {@code main} runs it, minus {@code System.exit}. */
class AIsolateCliTest {

  @TempDir Path empty;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  /** The banner prints before picocli dispatches, so one command stands for all of them. */
  @Test
  void givenNoProject_whenBuildExecuted_thenBannerThenTheErrorAndExit1() {
    // Arrange
    String[] args = {"build", "-p=" + empty};

    // Act
    int exit = execute(args);

    // Assert
    assertThat(err.toString(StandardCharsets.UTF_8))
        .startsWith("╭─ AIsolate v" + VersionConstants.VERSION + " ")
        .contains("no aisolate.yaml in ");
    assertThat(exit).isEqualTo(1);
  }

  @Test
  void givenVersionOption_whenExecuted_thenStdoutIsTheVersionLineAlone() {
    // Arrange
    String[] args = {"--version"};

    // Act
    int exit = execute(args);

    // Assert
    assertThat(exit).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8))
        .isEqualTo("aisolate " + VersionConstants.VERSION + System.lineSeparator());
  }

  private int execute(String[] args) {
    return AIsolateCli.execute(
        args,
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8),
        false);
  }
}
