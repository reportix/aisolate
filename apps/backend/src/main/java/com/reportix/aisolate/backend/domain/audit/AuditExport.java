package com.reportix.aisolate.backend.domain.audit;

import com.reportix.aisolate.auditreport.AuditReport;
import com.reportix.aisolate.backend.infrastructure.audit.AuditProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit export's PDF and XBRL: the lines {@link AuditService#all} found, as a plain report to
 * read and as the AIsolate Audit XBRL report.
 */
@Service
public class AuditExport {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  private final AuditReport.Entity entity;

  public AuditExport(AuditProperties properties) {
    try {
      entity = AuditReport.Entity.parse(properties.entity());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "AISOLATE_AUDIT_ENTITY must be an LEI or <scheme>:<id>, not " + properties.entity());
    }
  }

  /**
   * What the PDF's first lines say.
   *
   * @param from the export's {@code from}, null for no lower bound; {@code to} likewise
   */
  public record Heading(
      String space, String search, String from, String to, Instant generated, String by) {}

  /**
   * The xBRL-XML instance, UTF-8. Its period is the search's, in whole UTC days: from the oldest
   * line's day without {@code from}, to today without {@code to}. A line the report cannot use is
   * left out.
   */
  public byte[] xbrl(List<AuditRepository.Row> rows, String from, String to) {
    List<com.reportix.aisolate.auditreport.AuditLine> lines = new ArrayList<>();
    for (AuditRepository.Row row : rows) {
      try {
        lines.add(com.reportix.aisolate.auditreport.AuditLine.parse(row.body()));
      } catch (IllegalArgumentException unusable) {
        // the report's own checks are stricter than the ingest's: a verdict, a count
      }
    }
    lines.removeIf(Objects::isNull);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate first =
        !blank(from)
            ? day(from)
            : lines.stream().map(l -> day(l.ts())).min(Comparator.naturalOrder()).orElse(today);
    LocalDate last =
        !blank(to)
            ? day(to)
            : lines.stream()
                .map(l -> day(l.ts()))
                .filter(today::isBefore)
                .max(Comparator.naturalOrder())
                .orElse(today);
    return AuditReport.of(
            lines, new AuditReport.Period(first, last.isBefore(first) ? first : last), entity)
        .xml()
        .getBytes(StandardCharsets.UTF_8);
  }

  private static LocalDate day(String value) {
    return value.length() == 10 ? LocalDate.parse(value) : day(Instant.parse(value));
  }

  private static LocalDate day(Instant instant) {
    return LocalDate.ofInstant(instant, ZoneOffset.UTC);
  }

  /** The lines as they come, one block each: plain text on A4, a new page when one is full. */
  public byte[] pdf(List<AuditRepository.Row> rows, Heading heading) {
    try (Pdf pdf = new Pdf()) {
      pdf.title("AIsolate audit report");
      if (!blank(heading.space())) pdf.field("Space", heading.space());
      pdf.field("Search", blank(heading.search()) ? "none" : heading.search());
      pdf.field("Period", period(heading.from(), heading.to()));
      pdf.field("Generated", UTC.format(heading.generated()) + " by " + heading.by());
      pdf.field("Events", String.valueOf(rows.size()));
      for (AuditRepository.Row row : rows) {
        AuditLine.Columns c = row.columns();
        pdf.block(UTC.format(c.ts()) + "    " + c.space() + " / " + c.airlock());
        // A machine client asks without an e-mail: its client id says who.
        pdf.field("User", c.user() != null ? c.user() : c.client());
        pdf.field("Outcome", c.reason() == null ? c.outcome() : c.outcome() + ", " + c.reason());
        pdf.field("Question", c.question());
        pdf.field("Answer", c.answer());
        pdf.field("Tools", tools(row.body()));
        pdf.field(
            "Tokens",
            c.inputTokens() == null && c.outputTokens() == null
                ? null
                : String.format(
                    Locale.ROOT,
                    "%,d in, %,d out",
                    Objects.requireNonNullElse(c.inputTokens(), 0),
                    Objects.requireNonNullElse(c.outputTokens(), 0)));
        pdf.field(
            "Duration",
            c.durationMs() == null
                ? null
                : String.format(Locale.ROOT, "%.1f s", c.durationMs() / 1000.0));
      }
      return pdf.bytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String period(String from, String to) {
    if (blank(from) && blank(to)) return "all time";
    return (blank(from) ? "the first line" : moment(from))
        + " to "
        + (blank(to) ? "now" : moment(to));
  }

  private static String moment(String value) {
    return value.length() == 10 ? value : UTC.format(Instant.parse(value));
  }

  /** "read_records (2 rows), read_my_records (unreachable)"; null without a tool call. */
  private static String tools(String body) {
    List<String> calls = new ArrayList<>();
    for (JsonNode tool : JSON.readTree(body).path("tools")) {
      JsonNode rows = tool.path("rows");
      calls.add(
          tool.path("name").asString("?")
              + (rows.isIntegralNumber()
                  ? " (" + rows.asLong() + (rows.asLong() == 1 ? " row)" : " rows)")
                  : " (" + tool.path("error").asString("failed") + ")"));
    }
    return calls.isEmpty() ? null : String.join(", ", calls);
  }

  /** A text cursor down A4 pages. */
  private static final class Pdf implements AutoCloseable {

    private static final float MARGIN = 50;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float LABEL = 70;
    private static final float SIZE = 9.5f;
    private static final float LEADING = 13;

    private final PDDocument doc = new PDDocument();
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    // PDFBox's own Liberation Sans, embedded: the standard fonts know Western European text alone.
    private final PDFont text;
    private final Map<Integer, Boolean> glyphs = new HashMap<>();
    private PDPageContentStream page;
    private float y;

    Pdf() throws IOException {
      try (InputStream ttf =
          PDDocument.class.getResourceAsStream(
              "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
        text = PDType0Font.load(doc, ttf);
      }
      doc.getDocumentInformation().setTitle("AIsolate audit report");
      newPage();
    }

    void title(String title) throws IOException {
      show(bold, 16, MARGIN, title);
      y -= 2 * LEADING;
    }

    /** An exchange's first line, after a gap; on a new page when its first lines would not fit. */
    void block(String head) throws IOException {
      y -= LEADING;
      if (y < MARGIN + 4 * LEADING) newPage();
      show(text, 11, MARGIN, head);
      y -= LEADING + 2;
    }

    /** The label, then the value wrapped beside it; a dash for none. */
    void field(String label, String value) throws IOException {
      List<String> lines = wrap(value == null ? "—" : value.strip(), WIDTH - LABEL);
      for (int i = 0; i < lines.size(); i++) {
        if (y < MARGIN) newPage();
        if (i == 0) show(bold, SIZE, MARGIN, label);
        show(text, SIZE, MARGIN + LABEL, lines.get(i));
        y -= LEADING;
      }
    }

    byte[] bytes() throws IOException {
      page.close();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
      doc.close();
    }

    private void newPage() throws IOException {
      if (page != null) page.close();
      PDPage next = new PDPage(PDRectangle.A4);
      doc.addPage(next);
      page = new PDPageContentStream(doc, next);
      y = PDRectangle.A4.getHeight() - MARGIN;
    }

    private void show(PDFont font, float size, float x, String s) throws IOException {
      page.beginText();
      page.setFont(font, size);
      page.newLineAtOffset(x, y);
      page.showText(s);
      page.endText();
    }

    /** Lines of at most {@code width} at the text size: by word, a longer word cut. */
    private List<String> wrap(String value, float width) throws IOException {
      List<String> lines = new ArrayList<>();
      for (String paragraph : printable(value).split("\n", -1)) {
        String line = "";
        for (String word : paragraph.split(" ", -1)) {
          String joined = line.isEmpty() ? word : line + " " + word;
          if (width(joined) <= width) {
            line = joined;
            continue;
          }
          if (!line.isEmpty()) lines.add(line);
          line = word;
          while (width(line) > width) {
            int cut = line.length() - 1;
            while (cut > 1 && width(line.substring(0, cut)) > width) cut--;
            lines.add(line.substring(0, cut));
            line = line.substring(cut);
          }
        }
        lines.add(line);
      }
      return lines;
    }

    private float width(String s) throws IOException {
      return text.getStringWidth(s) / 1000 * SIZE;
    }

    /**
     * What the font can draw: a tab or a control character a space; a glyph it lacks "?", or "-"
     * for a dash (the models write non-breaking hyphens).
     */
    private String printable(String value) {
      StringBuilder out = new StringBuilder();
      value
          .replace("\r\n", "\n")
          .codePoints()
          .forEach(
              cp -> {
                if (cp == '\n') out.append('\n');
                else if (Character.isISOControl(cp) || Character.isWhitespace(cp)) out.append(' ');
                else if (glyphs.computeIfAbsent(cp, this::drawable)) out.appendCodePoint(cp);
                else out.append(Character.getType(cp) == Character.DASH_PUNCTUATION ? '-' : '?');
              });
      return out.toString();
    }

    private boolean drawable(int codePoint) {
      try {
        text.encode(Character.toString(codePoint));
        return true;
      } catch (IllegalArgumentException | IOException missing) {
        return false;
      }
    }
  }
}
