package com.reportix.aisolate.backend.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.infrastructure.audit.AuditProperties;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The PDF names who asked: a person by e-mail, a machine client by its client id. */
class AuditExportTest {

  @ParameterizedTest
  @CsvSource({
    "'\"user\":\"eric@example.com\",\"client\":\"acme-chat\",', eric@example.com",
    "'\"client\":\"acme-batch\",', acme-batch"
  })
  void givenAsker_whenPdfExported_thenTheUserFieldIsTheEmailOrTheMachineClientsId(
      String asker, String user) throws IOException {
    // Arrange
    String body =
        "{\"ts\":\"2026-10-03T09:00:00Z\",\"event\":\"exchange\",\"space\":\"aldermoor\","
            + "\"airlock\":\"cardiology\","
            + asker
            + "\"outcome\":\"error\",\"duration_ms\":5}";
    AuditRepository.Row row =
        new AuditRepository.Row(1, Instant.now(), AuditLine.columnsOf(body), null, null, body);
    AuditExport export =
        new AuditExport(new AuditProperties(null, 8085, 84, "529900ALDERMOORHSP32"));

    // Act
    byte[] pdf =
        export.pdf(
            List.of(row),
            new AuditExport.Heading("aldermoor", null, null, null, Instant.now(), "eric"));

    // Assert
    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertThat(new PDFTextStripper().getText(document)).contains("User " + user);
    }
  }
}
