package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatesTest {

  @TempDir Path parent;

  @Test
  void givenDocumentsPage_whenRead_thenItsProjectFileIsTheHrTemplatesVerbatim() throws Exception {
    // Arrange: the page says it shows the file as create writes it, with the template's airlock
    String template =
        Files.readString(Path.of("src/main/templates/company-hr/aisolate.yaml"))
            .replace("${AIRLOCK}", "hr-contracts");

    // Act
    String page = Files.readString(Path.of("../../docs/documents.md"));

    // Assert
    assertThat(page).contains("```yaml\n" + template + "```\n");
  }

  @Test
  void givenEntryClimbingOutOfTheTarget_whenExtract_thenRefusedAndNothingWrittenOutside()
      throws Exception {
    // Arrange
    InputStream templates = zip(Map.of("hr/../x", "escaped"));
    Path target = parent.resolve("hr-lab");

    // Act
    Throwable thrown =
        catchThrowable(
            () ->
                Templates.extract(
                    templates, "hr", Map.of("SPACE", "hr-lab", "AIRLOCK", "hr"), target));

    // Assert
    assertThat(thrown).isInstanceOf(IOException.class).hasMessage("bad entry hr/../x");
    assertThat(parent.resolve("x")).doesNotExist();
  }

  @Test
  void givenListedAndUnlistedExtensions_whenExtract_thenOnlyListedOnesGetTheSpaceAndAirlock()
      throws Exception {
    // Arrange
    String text = "space: ${SPACE}\nairlock: ${AIRLOCK}\n";
    InputStream templates = zip(Map.of("hr/aisolate.yaml", text, "hr/notes.cfg", text));
    Path target = parent.resolve("hr-lab");

    // Act
    Templates.extract(templates, "hr", Map.of("SPACE", "hr-lab", "AIRLOCK", "payroll"), target);

    // Assert
    assertThat(target.resolve("aisolate.yaml")).hasContent("space: hr-lab\nairlock: payroll");
    assertThat(target.resolve("notes.cfg")).hasContent(text.strip());
  }

  private static InputStream zip(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return new ByteArrayInputStream(bytes.toByteArray());
  }
}
