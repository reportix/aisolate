package com.reportix.aisolate.auditreport;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The AIsolate Audit report of one period, as one xBRL-XML instance: a row per exchange, per tool
 * call and per verifier run, each a context of its own. The backend's audit export writes it.
 */
public final class AuditReport {

  static final String NAMESPACE = "https://aisolate.ai/xbrl/audit/2026-10-26";
  static final String SCHEMA = NAMESPACE + "/aisolate-audit-2026-10-26.xsd";
  private static final String LEI = "http://standards.iso.org/iso/17442";

  /** The airlock's rule ({@code Answers.isDontKnow}), copied: AuditOutcomeTest keeps them equal. */
  private static final Pattern LEADING_MARKS = Pattern.compile("^[\\s*_>\"'`“‘]+");

  private static final Pattern DONT_KNOW =
      Pattern.compile("i don['’]t know(?!\\p{Alnum})", Pattern.CASE_INSENSITIVE);

  public static boolean isDontKnow(String answer) {
    String text = LEADING_MARKS.matcher(answer).replaceFirst("");
    return text.isBlank() || DONT_KNOW.matcher(text).lookingAt();
  }

  /**
   * The outcome as the current airlock would log it: an older airlock logged an "I don't know"
   * answer as {@code answered}.
   */
  static String outcomeOf(String logged, String answer) {
    return "answered".equals(logged) && answer != null && isDontKnow(answer) ? "dont_know" : logged;
  }

  /**
   * A column: a key whose cells are a typed dimension's value, the element of its domain {@code
   * concept}, or a fact of a concept. A count is a pure number.
   */
  record Column(String name, String dimension, String concept, boolean count) {
    static Column key(String name, String axis, String domain) {
      return new Column(name, "aisolate:" + axis, "aisolate:" + domain, false);
    }

    static Column fact(String name, String concept) {
      return new Column(name, null, "aisolate:" + concept, false);
    }

    static Column count(String name, String concept) {
      return new Column(name, null, "aisolate:" + concept, true);
    }
  }

  record Table(String name, List<Column> columns) {}

  private static final Column EXCHANGE = Column.key("exchange", "ExchangeAxis", "ExchangeId");

  static final Table EXCHANGES =
      new Table(
          "exchanges",
          List.of(
              EXCHANGE,
              Column.fact("space", "Space"),
              Column.fact("airlock", "Airlock"),
              Column.fact("time", "ExchangeTime"),
              Column.fact("user", "User"),
              Column.fact("question", "Question"),
              Column.fact("answer", "Answer"),
              Column.fact("outcome", "Outcome"),
              Column.fact("model", "Model"),
              Column.count("input_tokens", "InputTokens"),
              Column.count("output_tokens", "OutputTokens"),
              Column.fact("duration", "Duration")));
  static final Table TOOL_CALLS =
      new Table(
          "tool_calls",
          List.of(
              EXCHANGE,
              Column.key("tool_call", "ToolCallAxis", "ToolCallPosition"),
              Column.fact("tool", "ToolName"),
              Column.fact("data_source", "DataSource"),
              Column.count("rows_returned", "RowsReturned")));
  static final Table VERIFIERS =
      new Table(
          "verifiers",
          List.of(
              EXCHANGE,
              Column.key("verifier_run", "VerifierAxis", "VerifierPosition"),
              Column.fact("verifier", "VerifierName"),
              Column.fact("verdict", "VerifierVerdict")));

  /** The audit period: two UTC dates, both included. */
  public record Period(LocalDate from, LocalDate to) {

    boolean contains(Instant ts) {
      return !ts.isBefore(start()) && ts.isBefore(end());
    }

    private Instant start() {
      return from.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant end() {
      return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
  }

  /** The reporting organisation: an identifier scheme and its identifier in it. */
  public record Entity(String scheme, String id) {

    /** {@code <scheme>:<id>}, or an LEI alone. */
    public static Entity parse(String entity) {
      int colon = entity.lastIndexOf(':');
      if (colon < 0 && !entity.isBlank()) {
        return new Entity(LEI, entity);
      }
      if (colon <= 0 || colon == entity.length() - 1) {
        throw new IllegalArgumentException(
            "AISOLATE_AUDIT_ENTITY must be an LEI or <scheme>:<id>, for example " + LEI + ":<LEI>");
      }
      return new Entity(entity.substring(0, colon), entity.substring(colon + 1));
    }

    String prefix() {
      return LEI.equals(scheme) ? "lei" : "scheme";
    }
  }

  private final Period period;
  private final Entity entity;
  private final Map<Table, List<Map<String, String>>> rows = new LinkedHashMap<>();
  private int exchanges;
  private int repeated;
  private int conflicting;

  private AuditReport(Period period, Entity entity) {
    this.period = period;
    this.entity = entity;
    for (Table table : List.of(EXCHANGES, TOOL_CALLS, VERIFIERS)) {
      rows.put(table, new ArrayList<>());
    }
  }

  /**
   * The report of the lines within the period. A refusal reports no answer, tool, verifier or
   * model. An exchange is reported once, from its first line: overlapping exports ({@code kubectl
   * logs} with {@code --previous}, two log queries) repeat lines.
   */
  public static AuditReport of(List<AuditLine> lines, Period period, Entity entity) {
    AuditReport report = new AuditReport(period, entity);
    Map<String, AuditLine> firstLines = new LinkedHashMap<>();
    for (AuditLine line : lines) {
      if (!period.contains(line.ts())) {
        continue;
      }
      AuditLine first = firstLines.putIfAbsent(exchangeKey(line), line);
      if (first != null && first.equals(line)) {
        report.repeated++;
      } else if (first != null) {
        report.conflicting++;
      }
    }
    firstLines.values().stream()
        .map(AuditReport::asReported)
        .sorted(Comparator.comparing(AuditLine::ts))
        .forEach(report::add);
    return report;
  }

  private static AuditLine asReported(AuditLine line) {
    if (!line.refused()) {
      return line;
    }
    return new AuditLine(
        line.ts(),
        line.traceId(),
        line.space(),
        line.airlock(),
        line.user(),
        line.question(),
        null,
        line.outcome(),
        List.of(),
        List.of(),
        null,
        null,
        null,
        line.durationMs());
  }

  private void add(AuditLine line) {
    exchanges++;
    String exchange = exchangeKey(line);
    rows.get(EXCHANGES)
        .add(
            new Row(exchange)
                .with("space", line.space())
                .with("airlock", line.airlock())
                .with("time", line.ts())
                .with("user", line.user())
                .with("question", line.question())
                .with("answer", line.answer())
                .with("outcome", line.outcome())
                .with("model", line.model())
                .with("input_tokens", line.inputTokens())
                .with("output_tokens", line.outputTokens())
                .with("duration", Duration.ofMillis(line.durationMs())));
    for (int i = 0; i < line.tools().size(); i++) {
      AuditLine.Tool tool = line.tools().get(i);
      rows.get(TOOL_CALLS)
          .add(
              new Row(exchange)
                  .with("tool_call", i + 1)
                  .with("tool", tool.name())
                  .with("data_source", tool.dataSource())
                  .with("rows_returned", tool.rows()));
    }
    for (int i = 0; i < line.verifiers().size(); i++) {
      AuditLine.Verifier verifier = line.verifiers().get(i);
      rows.get(VERIFIERS)
          .add(
              new Row(exchange)
                  .with("verifier_run", i + 1)
                  .with("verifier", verifier.name())
                  .with("verdict", verifier.verdict()));
    }
  }

  // Limit: without a trace id the start time names the exchange, so two in one millisecond
  // collide (the second is left out and counted). The airlock's tracing fills trace_id.
  private static String exchangeKey(AuditLine line) {
    return line.traceId() != null ? line.traceId() : line.ts().toString();
  }

  public int exchanges() {
    return exchanges;
  }

  /** Lines that repeat an exchange's first line exactly: dropped, the exchange is reported. */
  public int repeated() {
    return repeated;
  }

  /** Lines that repeat an exchange with other facts: left out, its first line is reported. */
  public int conflicting() {
    return conflicting;
  }

  public int facts() {
    return (int)
        rows.entrySet().stream()
            .flatMap(
                table ->
                    table.getValue().stream()
                        .flatMap(
                            row ->
                                table.getKey().columns().stream()
                                    .filter(
                                        c -> c.dimension() == null && row.containsKey(c.name()))))
            .count();
  }

  /**
   * The report as one xBRL-XML instance: a context per row, its keys as typed dimensions, and the
   * row's facts. A null cell is no fact, an empty one an empty string.
   */
  public String xml() {
    StringBuilder xml =
        new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<xbrl xmlns=\"http://www.xbrl.org/2003/instance\"\n")
            .append("  xmlns:xbrli=\"http://www.xbrl.org/2003/instance\"\n")
            .append("  xmlns:aisolate=\"" + NAMESPACE + "\"\n")
            .append("  xmlns:link=\"http://www.xbrl.org/2003/linkbase\"\n")
            .append("  xmlns:xlink=\"http://www.w3.org/1999/xlink\"\n")
            .append("  xmlns:xbrldi=\"http://xbrl.org/2006/xbrldi\"\n")
            .append("  xmlns:" + entity.prefix() + "=\"" + escape(entity.scheme()) + "\">\n")
            .append("  <link:schemaRef xlink:type=\"simple\" xlink:href=\"" + SCHEMA + "\"/>\n")
            .append("  <unit id=\"u-1\">\n    <measure>xbrli:pure</measure>\n  </unit>\n");
    int contexts = 0;
    for (Map.Entry<Table, List<Map<String, String>>> table : rows.entrySet()) {
      List<Column> columns = table.getKey().columns();
      for (Map<String, String> row : table.getValue()) {
        String context = "c-" + ++contexts;
        xml.append("  <context id=\"" + context + "\">\n")
            .append("    <entity>\n")
            .append("      <identifier scheme=\"" + escape(entity.scheme()) + "\">")
            .append(escape(entity.id()) + "</identifier>\n")
            .append("    </entity>\n")
            .append("    <period>\n")
            .append("      <startDate>" + period.from() + "</startDate>\n")
            .append("      <endDate>" + period.to() + "</endDate>\n")
            .append("    </period>\n")
            .append("    <scenario>\n");
        for (Column key : columns) {
          if (key.dimension() != null) {
            xml.append("      <xbrldi:typedMember dimension=\"" + key.dimension() + "\">\n")
                .append("        " + element(key.concept(), "", row.get(key.name())))
                .append("      </xbrldi:typedMember>\n");
          }
        }
        xml.append("    </scenario>\n  </context>\n");
        for (Column fact : columns) {
          String value = row.get(fact.name());
          if (fact.dimension() == null && value != null) {
            String attributes =
                " contextRef=\""
                    + context
                    + "\""
                    + (fact.count() ? " unitRef=\"u-1\" decimals=\"INF\"" : "");
            xml.append("  " + element(fact.concept(), attributes, value));
          }
        }
      }
    }
    return xml.append("</xbrl>\n").toString();
  }

  private static String element(String name, String attributes, String value) {
    return value.isEmpty()
        ? "<" + name + attributes + "/>\n"
        : "<" + name + attributes + ">" + escape(value) + "</" + name + ">\n";
  }

  /**
   * Text and attribute values escaped; a character XML 1.0 cannot hold is U+FFFD, a CR a reference
   * (a parser would read a bare one as a line feed).
   */
  private static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints()
        .forEach(
            c -> {
              switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\r' -> out.append("&#13;");
                default ->
                    out.appendCodePoint(
                        c == '\t'
                                || c == '\n'
                                || (c >= 0x20 && c <= 0xD7FF)
                                || (c >= 0xE000 && c <= 0xFFFD)
                                || c >= 0x10000
                            ? c
                            : 0xFFFD);
              }
            });
    return out.toString();
  }

  /** A row's cells by column name, the exchange's first; a null is no fact. */
  private static final class Row extends LinkedHashMap<String, String> {
    Row(String exchange) {
      put(EXCHANGE.name(), exchange);
    }

    Row with(String column, Object value) {
      if (value != null) {
        put(column, value.toString());
      }
      return this;
    }
  }
}
