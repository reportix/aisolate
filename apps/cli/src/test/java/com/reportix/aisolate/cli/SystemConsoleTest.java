package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SystemConsoleTest {

  private static final String NL = System.lineSeparator();

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @Test
  void givenColorOff_whenEveryKind_thenSymbolsWithoutEscapesAndErrorsOnStderr() {
    // Arrange
    Console console = console(false);

    // Act
    console.println("plain");
    console.info("building");
    console.success("built");
    console.warn("careful");
    console.error("failed");

    // Assert
    assertThat(out.toString(StandardCharsets.UTF_8))
        .isEqualTo(
            "plain"
                + NL
                + SystemConsole.INFO
                + "  building"
                + NL
                + SystemConsole.SUCCESS
                + "  built"
                + NL
                + SystemConsole.WARNING
                + "  careful"
                + NL);
    assertThat(err.toString(StandardCharsets.UTF_8))
        .isEqualTo(SystemConsole.ERROR + "  failed" + NL);
  }

  @Test
  void givenColorOn_whenError_thenOnlyTheSymbolIsColored() {
    // Arrange
    Console console = console(true);

    // Act
    console.error("failed");

    // Assert
    assertThat(err.toString(StandardCharsets.UTF_8))
        .isEqualTo("\u001b[38;5;203m" + SystemConsole.ERROR + "\u001b[0m  failed" + NL);
  }

  @ParameterizedTest
  @CsvSource({", true", "UTF-8, true", "utf8, true", "Cp437, false", "windows-1252, false"})
  void givenStdoutEncoding_whenChecked_thenUnicodeOnlyForUtf8(String encoding, boolean unicode) {
    // Arrange

    // Act
    boolean utf8 = SystemConsole.utf8(encoding);

    // Assert
    assertThat(utf8).isEqualTo(unicode);
  }

  private Console console(boolean color) {
    return new SystemConsole(
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8),
        color);
  }
}
