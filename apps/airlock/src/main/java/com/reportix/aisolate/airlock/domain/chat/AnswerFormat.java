package com.reportix.aisolate.airlock.domain.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model's final answer as AIsolate asks for it, the same for every airlock: text with
 * placeholders, and for each placeholder a reference into the exchange's evidence, never a typed
 * value (docs/verification.md). The instruction and the schema go into the system message; the
 * final turn is constrained to the schema where the provider supports it.
 */
final class AnswerFormat {

  static final List<String> OPS =
      List.of(
          "add",
          "subtract",
          "multiply",
          "divide",
          "percent_change",
          "sum",
          "average",
          "min",
          "max",
          "count",
          "top_n",
          "bottom_n");

  private static final String CELL =
      "{\"type\":\"object\",\"properties\":{\"evidence\":{\"type\":\"string\"},"
          + "\"row\":{\"type\":\"string\"},\"column\":{\"type\":\"string\"}},"
          + "\"required\":[\"evidence\",\"row\",\"column\"]}";

  private static final String ROWS = "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}";

  /** Inline, no {@code $ref}: a provider turning the schema into a grammar may not follow one. */
  static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{"
          + "\"text\":{\"type\":\"string\"},"
          + "\"figures\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
          + "\"id\":{\"type\":\"string\"},"
          + "\"cell\":"
          + CELL
          + ",\"computed\":{\"type\":\"object\",\"properties\":{"
          + "\"op\":{\"type\":\"string\",\"enum\":["
          + String.join(",", OPS.stream().map(op -> "\"" + op + "\"").toList())
          + "]},\"of\":{\"type\":\"array\",\"items\":"
          + CELL
          + "},\"n\":{\"type\":\"integer\"},"
          + "\"by\":{\"type\":\"object\",\"properties\":{\"evidence\":{\"type\":\"string\"},"
          + "\"column\":{\"type\":\"string\"}},\"required\":[\"evidence\",\"column\"]},"
          + "\"rows\":"
          + ROWS
          + "},\"required\":[\"op\"]},"
          + "\"table\":{\"type\":\"object\",\"properties\":{\"evidence\":{\"type\":\"string\"},"
          + "\"rows\":"
          + ROWS
          + ",\"columns\":"
          + ROWS
          + ",\"rank\":{\"type\":\"object\",\"properties\":{"
          + "\"op\":{\"type\":\"string\",\"enum\":[\"top_n\",\"bottom_n\"]},"
          + "\"by\":{\"type\":\"string\"}},\"required\":[\"op\",\"by\"]}},"
          + "\"required\":[\"evidence\",\"rows\",\"columns\"]},"
          + "\"about\":{\"type\":\"object\",\"properties\":{\"subject\":{\"type\":\"string\"},"
          + "\"period\":{\"type\":\"string\"},\"concept\":{\"type\":\"string\"},"
          + "\"unit\":{\"type\":\"string\"}}}},"
          + "\"required\":[\"id\"]}},"
          + "\"quotes\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
          + "\"id\":{\"type\":\"string\"},"
          + "\"passage\":{\"type\":\"object\",\"properties\":{\"evidence\":{\"type\":\"string\"},"
          + "\"index\":{\"type\":\"integer\"}},\"required\":[\"evidence\",\"index\"]},"
          + "\"text\":{\"type\":\"string\"}},\"required\":[\"id\",\"passage\",\"text\"]}},"
          + "\"dont_know\":{\"type\":\"boolean\"}},"
          + "\"required\":[\"text\",\"figures\",\"quotes\",\"dont_know\"]}";

  static final String INSTRUCTION =
      "Answer format. Your final answer is one JSON object and nothing else, with text, figures,"
          + " quotes and dont_know. Never type a cell of a tool result into text, neither a"
          + " number nor a word (a date, a name, a drug, a status): write a placeholder such as"
          + " {f1} where it goes and describe it in figures, with the evidence id, the row handle"
          + " (r1, r2, ...) and the column exactly as the tool result shows them:\n"
          + "- one cell: {\"id\":\"f1\",\"cell\":{\"evidence\":\"ev-1\",\"row\":\"r1\","
          + "\"column\":\"gross_salary\"},\"about\":{\"subject\":\"self\",\"period\":\"2026\"}}"
          + " for the asking user's own row;\n"
          + "- one cell of another person's row: {\"id\":\"f1\",\"cell\":{\"evidence\":\"ev-1\","
          + "\"row\":\"r2\",\"column\":\"gross_salary\"},\"about\":{\"subject\":\"Nora Berg\","
          + "\"period\":\"2026\"}}, the subject as the row names them;\n"
          + "- a value computed from cells: {\"id\":\"f2\",\"computed\":{\"op\":\"subtract\","
          + "\"of\":[<cell>,<cell>]}}, op one of add, subtract, multiply, divide, percent_change"
          + " (from the first cell to the second), sum, average, min, max, count;\n"
          + "- the highest or lowest rows of one tool result: {\"id\":\"f3\",\"computed\":"
          + "{\"op\":\"top_n\",\"n\":3,\"by\":{\"evidence\":\"ev-1\",\"column\":\"amount_eur\"},"
          + "\"rows\":[\"r2\",\"r5\",\"r1\"]}} (bottom_n likewise; op max or min with one row for"
          + " the single highest or lowest);\n"
          + "- several rows as a table: {\"id\":\"t1\",\"table\":{\"evidence\":\"ev-1\","
          + "\"rows\":[\"r1\",\"r2\"],\"columns\":[\"client\",\"amount_eur\"],"
          + "\"rank\":{\"op\":\"top_n\",\"by\":\"amount_eur\"}}}, rank only when the rows are a"
          + " ranking, then in rank order.\n"
          + "AIsolate writes each value in with its unit: put no unit, sign or scale word next to a"
          + " placeholder and no placeholder for a unit alone: AIsolate writes each unit with its"
          + " value. For every figure, about says whom it is about as the row names them"
          + " (\"self\" for the asking user's own row) and which period, and may name the concept"
          + " (the column) and the unit. Words from a document are a quote,"
          + " {\"id\":\"q1\",\"passage\":{\"evidence\":\"ev-2\",\"index\":1},\"text\":\"the"
          + " exact words\"}, placed in text as {q1}. A value typed in text is shown to the user"
          + " as unsourced. When the tools and documents do not hold the answer, set dont_know to"
          + " true. The JSON schema of the answer:\n"
          + SCHEMA;

  /** Sent when the model's last answer was not in the format: once, then once more. */
  static final String ANSWER_IN_FORMAT =
      "Write your final answer now as one JSON object in the answer format, from the documents"
          + " above and these tool results, each under its evidence id, every value a placeholder:";

  static final String RETRY =
      "That answer is not in the answer format (%s). Write it again as one JSON object in the"
          + " answer format.";

  /** The answer the model gave, parsed; what it refers to is resolved later ({@link Grounding}). */
  record ModelAnswer(String text, List<Ref> figures, List<QuoteRef> quotes, boolean dontKnow) {}

  /** One figure: exactly one of cell, computed and table is expected; checked when resolved. */
  record Ref(String id, Cell cell, Computed computed, Table table, About about) {}

  record Cell(String evidence, String row, String column) {}

  record By(String evidence, String column) {}

  record Computed(String op, List<Cell> of, Integer n, By by, List<String> rows) {}

  record Rank(String op, String by) {}

  record Table(String evidence, List<String> rows, List<String> columns, Rank rank) {}

  /** The model's own claim of what a figure is about, checked against the row's labels. */
  record About(String subject, String period, String concept, String unit) {}

  record QuoteRef(String id, String evidence, Integer index, String text) {}

  private static final Pattern FENCE = Pattern.compile("^```(?:json)?\\s*|\\s*```$");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AnswerFormat() {}

  /**
   * The model's text as an answer in the format; a fenced block ({@code ```json}) is read as its
   * content.
   *
   * @throws IllegalArgumentException not in the format; the message says what, for the retry, and
   *     never quotes the text, which may hold rows
   */
  static ModelAnswer parse(String said) {
    String json = FENCE.matcher(said == null ? "" : said.strip()).replaceAll("");
    JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (JacksonException notJson) {
      throw new IllegalArgumentException("it is not JSON");
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("it is not a JSON object");
    }
    if (!root.path("text").isString()) {
      throw new IllegalArgumentException("text is missing");
    }
    List<Ref> figures = new ArrayList<>();
    for (JsonNode figure : array(root, "figures")) {
      String id = string(figure, "id");
      if (!figure.isObject() || id == null) {
        throw new IllegalArgumentException("every figure needs an id");
      }
      JsonNode computed = figure.path("computed");
      JsonNode table = figure.path("table");
      JsonNode about = figure.path("about");
      figures.add(
          new Ref(
              id,
              cell(figure.path("cell")),
              computed.isObject()
                  ? new Computed(
                      string(computed, "op"),
                      cells(computed.path("of")),
                      computed.path("n").isIntegralNumber() ? computed.path("n").asInt() : null,
                      computed.path("by").isObject()
                          ? new By(
                              string(computed.path("by"), "evidence"),
                              string(computed.path("by"), "column"))
                          : null,
                      strings(computed.path("rows")))
                  : null,
              table.isObject()
                  ? new Table(
                      string(table, "evidence"),
                      strings(table.path("rows")),
                      strings(table.path("columns")),
                      table.path("rank").isObject()
                          ? new Rank(
                              string(table.path("rank"), "op"), string(table.path("rank"), "by"))
                          : null)
                  : null,
              about.isObject()
                  ? new About(
                      string(about, "subject"),
                      string(about, "period"),
                      string(about, "concept"),
                      string(about, "unit"))
                  : null));
    }
    List<QuoteRef> quotes = new ArrayList<>();
    for (JsonNode quote : array(root, "quotes")) {
      JsonNode passage = quote.path("passage");
      quotes.add(
          new QuoteRef(
              string(quote, "id"),
              string(passage, "evidence"),
              passage.path("index").isIntegralNumber() ? passage.path("index").asInt() : null,
              string(quote, "text")));
    }
    return new ModelAnswer(
        root.path("text").asString(), figures, quotes, root.path("dont_know").asBoolean(false));
  }

  private static Iterable<JsonNode> array(JsonNode root, String field) {
    JsonNode items = root.path(field);
    if (items.isMissingNode() || items.isNull()) {
      return List.of();
    }
    if (!items.isArray()) {
      throw new IllegalArgumentException(field + " is not a list");
    }
    return items;
  }

  private static Cell cell(JsonNode node) {
    return node.isObject()
        ? new Cell(string(node, "evidence"), string(node, "row"), string(node, "column"))
        : null;
  }

  private static List<Cell> cells(JsonNode node) {
    List<Cell> cells = new ArrayList<>();
    node.forEach(item -> cells.add(cell(item)));
    return cells;
  }

  private static List<String> strings(JsonNode node) {
    List<String> strings = new ArrayList<>();
    // No column or handle is named so: an entry that is not a string resolves nowhere, a mismatch.
    node.forEach(
        item -> strings.add(item.isValueNode() && !item.isNull() ? item.asString() : "(none)"));
    return strings;
  }

  private static String string(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isValueNode() && !value.isNull() ? value.asString() : null;
  }
}
