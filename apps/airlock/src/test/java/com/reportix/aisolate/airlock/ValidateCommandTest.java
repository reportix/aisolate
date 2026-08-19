package com.reportix.aisolate.airlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.support.Documents;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidateCommandTest {

  @Test
  void givenFixtures_whenValidate_thenZeroAndRevisionAndEveryKindPrinted() {
    // Arrange
    StringWriter out = new StringWriter();

    // Act
    int code = ValidateCommand.run(Documents.DIR, new PrintWriter(out, true));

    // Assert
    assertThat(code).isZero();
    assertThat(out.toString())
        .contains("revision")
        .contains("spaces [hr-acme]")
        .contains("airlocks [hr-assistant, founders-finance]");
  }

  @Test
  void givenBrokenDirectory_whenValidate_thenOneAndProblemsPrinted(@TempDir Path dir)
      throws IOException {
    // Arrange
    Files.writeString(
        dir.resolve("a.yaml"),
        "apiVersion: aisolate.dev/v1\nkind: Airlock\nmetadata: {name: x}\nspec: {}\n");
    StringWriter out = new StringWriter();

    // Act
    int code = ValidateCommand.run(dir, new PrintWriter(out, true));

    // Assert
    assertThat(code).isEqualTo(1);
    assertThat(out.toString()).contains("a.yaml").contains("metadata.name");
  }

  @Test
  void givenMissingDirectory_whenValidate_thenOneAndPathPrinted(@TempDir Path dir) {
    // Arrange
    Path missing = dir.resolve("nowhere");
    StringWriter out = new StringWriter();

    // Act
    int code = ValidateCommand.run(missing, new PrintWriter(out, true));

    // Assert
    assertThat(code).isEqualTo(1);
    assertThat(out.toString()).contains("nowhere");
  }
}
