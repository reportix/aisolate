package com.reportix.aisolate.auditreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The taxonomy's examples (taxonomy/audit/examples/VALIDATION.md): each {@code report.xbrl} is the
 * report of its {@code audit.log}. Delete one to write it again.
 */
class AuditReportExamplesTest {

  private static final Path EXAMPLES = Path.of(System.getProperty("aisolate.audit.examples"));
  private static final LocalDate DAY = LocalDate.parse("2026-10-19");

  @ParameterizedTest
  @CsvSource({
    "acme, 529900ACMEFINGROUP15",
    "halvard-finch, 529900HALVARDFINCH06",
    "aldermoor, 529900ALDERMOORHSP32"
  })
  void givenAnExamplesAuditLog_whenReportedForItsDay_thenItsReport(String example, String lei)
      throws IOException {
    // Arrange
    Path dir = EXAMPLES.resolve(example);
    List<AuditLine> lines =
        Files.readAllLines(dir.resolve("audit.log")).stream()
            .map(AuditLine::parse)
            .filter(Objects::nonNull)
            .toList();

    // Act
    String xml =
        AuditReport.of(lines, new AuditReport.Period(DAY, DAY), AuditReport.Entity.parse(lei))
            .xml();

    // Assert
    Path report = dir.resolve("report.xbrl");
    if (!Files.exists(report)) {
      Files.writeString(report, xml);
      fail("report written to " + report + ": review it, then run again");
    }
    assertThat(xml).isEqualTo(Files.readString(report));
  }
}
