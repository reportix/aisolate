package com.reportix.aisolate.airlock.domain.chat;

import com.reportix.aisolate.airlock.domain.chat.AnswerFormat.About;
import com.reportix.aisolate.airlock.domain.chat.AnswerFormat.ModelAnswer;
import com.reportix.aisolate.airlock.domain.chat.AnswerFormat.QuoteRef;
import com.reportix.aisolate.airlock.domain.chat.AnswerFormat.Ref;
import com.reportix.aisolate.airlock.domain.chat.GroundedAnswer.By;
import com.reportix.aisolate.airlock.domain.chat.GroundedAnswer.Call;
import com.reportix.aisolate.airlock.domain.chat.GroundedAnswer.Figure;
import com.reportix.aisolate.airlock.domain.chat.GroundedAnswer.Quote;
import com.reportix.aisolate.airlock.domain.chat.GroundedAnswer.Segment;
import com.reportix.aisolate.airlock.domain.chat.GroundedAnswer.Source;
import com.reportix.aisolate.airlock.domain.chat.GroundedAnswer.Table;
import com.reportix.aisolate.sdk.Passage;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns the model's answer into the user's: each placeholder resolved against this exchange's
 * evidence and written in by AIsolate, each claim checked, every number and fact value the model
 * typed itself flagged. Deterministic: the same answer over the same evidence gives the same
 * result, and no model is asked. What it proves and what it does not: docs/verification.md.
 *
 * <p>A figure is {@code sourced} (a cell that resolves, its labels agreeing with what the model
 * claimed), {@code computed} (an operation AIsolate evaluated over such cells, exact decimals), or
 * {@code mismatch} (a reference that does not resolve, a masked column, a label that conflicts, a
 * ranking the evidence contradicts, a sign, scale or unit the text puts next to it). Digits the
 * model typed, and the values of a fact column, are {@code unsourced}, unless the question holds
 * them.
 */
final class Grounding {

  static final String SOURCED = "sourced";
  static final String COMPUTED = "computed";
  static final String UNSOURCED = "unsourced";
  static final String MISMATCH = "mismatch";

  /** What a figure shows when there is no value to show: a reference that resolves nowhere. */
  static final String NO_VALUE = "…";

  /**
   * One tool result as an answer may cite it.
   *
   * @param byHandle the rows by their handle ({@code r1}, ...), numbered across the exchange
   */
  record Rows(
      String evidence,
      Call call,
      String entity,
      List<String> columns,
      ToolEvidence.Labels labels,
      Map<String, Map<String, Object>> byHandle) {}

  /** The passages of one evidence; index 1 is the first. */
  record Passages(String evidence, List<Passage> passages) {}

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_-]*)}");

  /** A token holding a digit: 78,000.50, 2026-03-14, INV-2026-0412, Q1. */
  private static final Pattern NUMBER =
      Pattern.compile(
          "(?<![\\p{L}\\p{N}])(?:[\\p{L}\\p{N}]+[.,:/-](?=[\\p{L}\\p{N}]))*[\\p{L}\\p{N}]*\\p{N}"
              + "(?:[\\p{L}\\p{N}]|[.,:/-](?=[\\p{L}\\p{N}]))*");

  private static final Pattern EVIDENCE_ID = Pattern.compile("(?i)ev-\\d+");

  private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:19|20)\\d\\d(?!\\d)");

  private static final Pattern ISO_LIKE = Pattern.compile("[0-9 ./:-]+");

  private static final Pattern SIGN_BEFORE =
      Pattern.compile("(?:^|[\\s(\\[:])[-−+]$|(?i)\\b(?:minus|plus|negative)\\s*$");

  private static final Pattern UNIT_BEFORE = Pattern.compile("([€$£]|\\b[A-Z]{3})\\s?$");

  private static final Pattern WORD_AFTER = Pattern.compile("^\\s?([%€$£]|\\p{L}[\\p{L}\\p{N}/]*)");

  /** A fact value's edges: never inside a word, nor inside a number token (the 5 mg of 2.5 mg). */
  private static final String FACT_BEFORE = "(?<![\\p{L}\\p{N}])(?<!\\p{N}[.,:/-])";

  private static final String FACT_AFTER = "(?![\\p{L}\\p{N}])(?![.,:/-]\\p{N})";

  private static final Set<String> SCALES =
      Set.of(
          "k",
          "m",
          "mn",
          "bn",
          "mio",
          "mrd",
          "tsd",
          "thousand",
          "thousands",
          "million",
          "millions",
          "billion",
          "billions",
          "trillion",
          "trillions");

  private static final Set<String> CURRENCIES =
      Set.of(
          "EUR", "USD", "GBP", "CHF", "JPY", "CNY", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "CAD",
          "AUD");

  private static final Map<String, String> SYMBOLS =
      Map.of("€", "EUR", "$", "USD", "£", "GBP", "euro", "EUR", "euros", "EUR", "percent", "%");

  private static final Set<String> SELF = Set.of("self", "me", "you", "the user", "asking user");

  private static final MathContext EXACT = MathContext.DECIMAL128;

  private final Map<String, Rows> rows = new LinkedHashMap<>();
  private final Map<String, Passages> passages = new LinkedHashMap<>();

  /** Which evidence each row handle belongs to: a handle from another evidence is a mismatch. */
  private final Map<String, String> handles = new HashMap<>();

  /** Every unit this exchange's evidence knows: a text naming one beside a figure claims it. */
  private final Set<String> units = new HashSet<>();

  private final Set<String> questionTokens;

  /**
   * Each value of a fact column, as {@link #factKey} writes it, and the first cell holding it:
   * {@code ev-3 r4 drug}.
   */
  private final Map<String, String> facts = new LinkedHashMap<>();

  /** The question as {@link #factKey} writes it: a fact value it names is not typed. */
  private final String questionKey;

  /** Any fact value as typed; null when the evidence holds none. */
  private final Pattern factPattern;

  /** Each figure's unit, by id, for the words beside its placeholder. */
  private final Map<String, String> figureUnits = new HashMap<>();

  Grounding(Collection<Rows> toolResults, Collection<Passages> documents, String question) {
    for (Rows result : toolResults) {
      rows.put(result.evidence(), result);
      result.byHandle().keySet().forEach(handle -> handles.put(handle, result.evidence()));
      units.addAll(result.labels().units().values());
      result
          .labels()
          .unitColumns()
          .values()
          .forEach(
              column ->
                  result.byHandle().values().stream()
                      .map(row -> row.get(column))
                      .filter(Objects::nonNull)
                      .forEach(unit -> units.add(String.valueOf(unit))));
      for (String column : result.labels().facts()) {
        result
            .byHandle()
            .forEach(
                (handle, row) -> {
                  if (row.get(column) instanceof String value
                      && value.strip().codePoints().count() >= 3) {
                    facts.putIfAbsent(
                        factKey(value), result.evidence() + " " + handle + " " + column);
                  }
                });
      }
    }
    documents.forEach(document -> passages.put(document.evidence(), document));
    questionTokens =
        NUMBER
            .matcher(question == null ? "" : question)
            .results()
            .map(token -> numberKey(token.group()))
            .collect(Collectors.toSet());
    questionKey = factKey(question == null ? "" : question);
    factPattern = facts.isEmpty() ? null : factPattern(facts.keySet());
  }

  /** The model's answer in the format, every placeholder resolved and every claim checked. */
  GroundedAnswer ground(ModelAnswer answer) {
    if (answer.dontKnow()) {
      return GroundedAnswer.plain(ChatExchange.I_DONT_KNOW);
    }
    Map<String, Ref> refs = new LinkedHashMap<>();
    answer.figures().forEach(ref -> refs.putIfAbsent(ref.id(), ref));
    Map<String, Quote> quotes = new LinkedHashMap<>();
    for (QuoteRef ref : answer.quotes()) {
      if (ref.id() != null && !refs.containsKey(ref.id())) {
        quotes.putIfAbsent(ref.id(), quote(ref));
      }
    }
    Render render = new Render(refs.keySet(), quotes.keySet());
    List<String> texts = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    Matcher placeholder = PLACEHOLDER.matcher(answer.text());
    int at = 0;
    while (placeholder.find()) {
      texts.add(answer.text().substring(at, placeholder.start()));
      ids.add(placeholder.group(1));
      at = placeholder.end();
    }
    texts.add(answer.text().substring(at));
    Map<String, Figure> figures = new LinkedHashMap<>();
    Map<String, Table> tables = new LinkedHashMap<>();
    // First every reference and the words around it; the text left over is scanned after.
    for (int i = 0; i < ids.size(); i++) {
      String id = ids.get(i);
      Ref ref = refs.get(id);
      if (ref != null && ref.table() != null) {
        tables.computeIfAbsent(id, key -> table(key, ref.table()));
      } else if (ref != null || !quotes.containsKey(id)) {
        Figure figure = figures.computeIfAbsent(id, key -> figure(key, ref));
        String unit = figureUnits.get(id);
        String[] around = {texts.get(i), texts.get(i + 1)};
        String conflict = besideFigure(around, figure.status(), unit);
        texts.set(i, around[0]);
        texts.set(i + 1, around[1]);
        if (conflict != null && !MISMATCH.equals(figure.status())) {
          figures.put(id, mismatch(figure, conflict));
        }
      }
    }
    for (int i = 0; i < ids.size(); i++) {
      render.text(texts.get(i), true);
      String id = ids.get(i);
      if (tables.containsKey(id)) {
        render.table(tables.get(id));
      } else if (figures.containsKey(id)) {
        render.figure(figures.get(id));
      } else {
        Quote quote = quotes.get(id);
        // Words not in the passage are the model's own: their numbers are unsourced too.
        render.text(
            "\"" + Objects.toString(quote.text(), "") + "\"", !"quoted".equals(quote.status()));
      }
    }
    render.text(texts.getLast(), true);
    return render.done(List.copyOf(quotes.values()));
  }

  /** Text that is not in the format: every number and fact value in it is unsourced. */
  GroundedAnswer raw(String text) {
    Render render = new Render(Set.of(), Set.of());
    render.text(text == null ? "" : text, true);
    return render.done(List.of());
  }

  // ── figures ──

  private Figure figure(String id, Ref ref) {
    try {
      if (ref == null) {
        throw new Mismatch("the answer places {" + id + "} but gives no reference for it");
      }
      if ((ref.cell() == null) == (ref.computed() == null)) {
        throw new Mismatch(
            ref.cell() == null
                ? id + " names neither a cell nor a computation"
                : id + " names both a cell and a computation");
      }
      if (ref.cell() != null) {
        Resolved cell = resolve(ref.cell());
        String conflict = conflict(ref.about(), List.of(cell), cell.unit(), List.of(cell.column()));
        Figure sourced =
            new Figure(id, cell.display(), cell.value(), SOURCED, cell.source(), null, null);
        figureUnits.put(id, cell.unit());
        return conflict == null ? sourced : mismatch(sourced, conflict);
      }
      return computed(id, ref.computed(), ref.about());
    } catch (Mismatch mismatch) {
      return new Figure(id, NO_VALUE, null, MISMATCH, null, null, mismatch.getMessage());
    }
  }

  private Figure computed(String id, AnswerFormat.Computed computed, About about) {
    String op = computed.op() == null ? "" : computed.op();
    if (!AnswerFormat.OPS.contains(op)) {
      throw new Mismatch("unknown operation " + op);
    }
    if (op.equals("top_n") || op.equals("bottom_n")) {
      return ranking(id, computed);
    }
    if ((op.equals("max") || op.equals("min")) && computed.by() != null) {
      return extreme(id, computed, about);
    }
    List<AnswerFormat.Cell> cells = computed.of() == null ? List.of() : computed.of();
    int arity = cells.size();
    boolean binary = op.equals("subtract") || op.equals("divide") || op.equals("percent_change");
    boolean several = op.equals("add") || op.equals("multiply");
    if (binary && arity != 2 || several && arity < 2 || arity < 1) {
      throw new Mismatch(
          op
              + " needs "
              + (binary ? "two cells" : several ? "two cells or more" : "a cell")
              + ", got "
              + arity);
    }
    List<Resolved> operands = cells.stream().map(this::resolve).toList();
    List<Source> of = operands.stream().map(Resolved::operand).toList();
    if (op.equals("count")) {
      return checked(
          id,
          String.valueOf(operands.size()),
          String.valueOf(operands.size()),
          null,
          new GroundedAnswer.Computed(op, of, null, null, null, null, null),
          about,
          operands);
    }
    List<BigDecimal> numbers = new ArrayList<>();
    for (Resolved operand : operands) {
      if (operand.number() == null) {
        throw new Mismatch(operand.where() + " is not a number");
      }
      numbers.add(operand.number());
    }
    List<String> operandUnits =
        operands.stream().map(Resolved::unit).filter(Objects::nonNull).distinct().toList();
    boolean sameUnit = operandUnits.size() <= 1;
    String unit = operandUnits.isEmpty() ? null : operandUnits.getFirst();
    BigDecimal result;
    Resolved picked = null;
    switch (op) {
      case "add", "sum" -> result = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      case "subtract" -> result = numbers.get(0).subtract(numbers.get(1));
      case "multiply" -> {
        result = numbers.stream().reduce(BigDecimal.ONE, BigDecimal::multiply);
        unit = operandUnits.size() == 1 ? unit : null;
        sameUnit = true;
      }
      case "divide" -> {
        result = divide(numbers.get(0), numbers.get(1));
        // EUR over days is a rate in no unit AIsolate names; EUR over a count stays EUR.
        String over = operands.get(1).unit();
        unit = over == null ? operands.get(0).unit() : null;
        sameUnit = true;
      }
      case "percent_change" -> {
        result =
            divide(numbers.get(1).subtract(numbers.get(0)), numbers.get(0))
                .multiply(BigDecimal.valueOf(100));
        unit = "%";
      }
      case "average" ->
          result =
              divide(
                  numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                  BigDecimal.valueOf(numbers.size()));
      default -> {
        // max or min over cells: the value, and which of them holds it
        int best = 0;
        for (int i = 1; i < numbers.size(); i++) {
          int order = numbers.get(i).compareTo(numbers.get(best));
          if (op.equals("max") ? order > 0 : order < 0) {
            best = i;
          }
        }
        result = numbers.get(best);
        picked = operands.get(best);
      }
    }
    if (!sameUnit) {
      throw new Mismatch(op + " mixes " + String.join(" and ", operandUnits));
    }
    boolean rounded = op.equals("divide") || op.equals("average") || op.equals("percent_change");
    BigDecimal exact = rounded ? result.setScale(10, RoundingMode.HALF_EVEN) : result;
    // The operands' declared decimals, where they agree; a percentage is no longer in their unit.
    List<Integer> declared = operands.stream().map(Resolved::decimals).distinct().toList();
    Integer decimals =
        declared.size() == 1 && !op.equals("percent_change") ? declared.getFirst() : null;
    String display = withUnit(group(shown(result, decimals, rounded), true), unit);
    GroundedAnswer.Computed provenance =
        new GroundedAnswer.Computed(
            op, of, null, null, null, picked == null ? null : picked.source(), null);
    // A max or a min is about the row that holds it, not every row it was taken over.
    return checked(
        id,
        display,
        plain(exact),
        unit,
        provenance,
        about,
        picked == null ? operands : List.of(picked));
  }

  private Figure checked(
      String id,
      String display,
      String value,
      String unit,
      GroundedAnswer.Computed computed,
      About about,
      List<Resolved> aboutRows) {
    Figure figure = new Figure(id, display, value, COMPUTED, null, computed, null);
    figureUnits.put(id, unit);
    String conflict =
        conflict(about, aboutRows, unit, aboutRows.stream().map(Resolved::column).toList());
    return conflict == null ? figure : mismatch(figure, conflict);
  }

  /** {@code top_n} and {@code bottom_n}: the cited rows are exactly the best n by the column. */
  private Figure ranking(String id, AnswerFormat.Computed computed) {
    AnswerFormat.By by = computed.by();
    if (by == null || computed.rows() == null || computed.rows().isEmpty()) {
      throw new Mismatch(computed.op() + " needs by (evidence and column) and the ranked rows");
    }
    Rows result = rowsOf(by.evidence());
    rankable(result, by.column());
    List<String> cited = computed.rows();
    List<Source> ranked = new ArrayList<>();
    for (String handle : cited) {
      ranked.add(resolve(new AnswerFormat.Cell(by.evidence(), handle, by.column())).operand());
    }
    String reason =
        computed.n() != null && computed.n() != cited.size()
            ? "n is " + computed.n() + " but " + cited.size() + " rows are ranked"
            : rankConflict(result, by.column(), cited, computed.op().equals("top_n"));
    String names = ranked.stream().map(Grounding::name).collect(Collectors.joining(", "));
    Figure figure =
        new Figure(
            id,
            names,
            null,
            COMPUTED,
            null,
            new GroundedAnswer.Computed(
                computed.op(),
                null,
                cited.size(),
                new By(by.evidence(), result.entity(), by.column()),
                ranked,
                null,
                result.call()),
            null);
    return reason == null ? figure : mismatch(figure, reason);
  }

  /** {@code max} or {@code min} over one evidence's rows: the value, and the row holding it. */
  private Figure extreme(String id, AnswerFormat.Computed computed, About about) {
    AnswerFormat.By by = computed.by();
    Rows result = rowsOf(by.evidence());
    rankable(result, by.column());
    boolean max = computed.op().equals("max");
    List<String> winners = best(result, by.column(), max);
    if (winners.isEmpty()) {
      throw new Mismatch(
          by.evidence() + " has no " + by.column() + " to take the " + computed.op() + " of");
    }
    List<String> cited = computed.rows() == null ? List.of() : computed.rows();
    String reason = null;
    for (String handle : cited) {
      rowOf(result, handle);
      if (!winners.contains(handle)) {
        reason =
            name(result, handle)
                + " does not hold the "
                + (max ? "highest " : "lowest ")
                + by.column()
                + " of "
                + by.evidence()
                + "; "
                + name(result, winners.getFirst())
                + " does";
        break;
      }
    }
    Resolved row =
        resolve(
            new AnswerFormat.Cell(
                by.evidence(),
                cited.isEmpty() ? winners.getFirst() : cited.getFirst(),
                by.column()));
    Figure figure =
        new Figure(
            id,
            row.display(),
            row.value(),
            COMPUTED,
            null,
            new GroundedAnswer.Computed(
                computed.op(),
                null,
                null,
                new By(by.evidence(), result.entity(), by.column()),
                null,
                row.source(),
                result.call()),
            null);
    figureUnits.put(id, row.unit());
    String conflict =
        reason != null ? reason : conflict(about, List.of(row), row.unit(), List.of(by.column()));
    return conflict == null ? figure : mismatch(figure, conflict);
  }

  // ── tables ──

  private Table table(String id, AnswerFormat.Table ref) {
    Rows result = rows.get(ref.evidence());
    List<String> columns = ref.columns() == null ? List.of() : ref.columns();
    List<String> cited = ref.rows() == null ? List.of() : ref.rows();
    String reason = null;
    try {
      rowsOf(ref.evidence());
      if (columns.isEmpty() || cited.isEmpty()) {
        throw new Mismatch("a table needs rows and columns");
      }
      if (new HashSet<>(cited).size() < cited.size()) {
        throw new Mismatch("a row is listed twice");
      }
      if (ref.rank() != null) {
        if (!"top_n".equals(ref.rank().op()) && !"bottom_n".equals(ref.rank().op())) {
          throw new Mismatch("unknown ranking " + ref.rank().op());
        }
        rankable(result, ref.rank().by());
        cited.forEach(handle -> rowOf(result, handle));
        reason = rankConflict(result, ref.rank().by(), cited, "top_n".equals(ref.rank().op()));
      }
    } catch (Mismatch mismatch) {
      reason = mismatch.getMessage();
    }
    List<List<GroundedAnswer.Cell>> cells = new ArrayList<>();
    StringBuilder markdown = new StringBuilder("| ");
    markdown
        .append(columns.stream().map(Grounding::cellText).collect(Collectors.joining(" | ")))
        .append(" |\n|");
    markdown.append(" --- |".repeat(columns.size()));
    for (String handle : cited) {
      List<GroundedAnswer.Cell> line = new ArrayList<>();
      for (String column : columns) {
        try {
          Resolved cell = resolve(new AnswerFormat.Cell(ref.evidence(), handle, column));
          line.add(new GroundedAnswer.Cell(cell.display(), cell.value(), SOURCED, cell.source()));
        } catch (Mismatch mismatch) {
          reason = reason != null ? reason : mismatch.getMessage();
          line.add(new GroundedAnswer.Cell(NO_VALUE, null, MISMATCH, null));
        }
      }
      cells.add(line);
      markdown
          .append("\n| ")
          .append(line.stream().map(c -> cellText(c.display())).collect(Collectors.joining(" | ")))
          .append(" |");
    }
    return new Table(
        id,
        reason == null ? SOURCED : MISMATCH,
        markdown.toString(),
        ref.evidence(),
        result == null ? null : result.entity(),
        result == null ? null : result.call(),
        columns,
        cells,
        ref.rank() == null ? null : new GroundedAnswer.Rank(ref.rank().op(), ref.rank().by()),
        reason);
  }

  // ── rankings ──

  private void rankable(Rows result, String column) {
    if (column == null || !result.columns().contains(column)) {
      throw new Mismatch(result.evidence() + " (" + result.entity() + ") has no column " + column);
    }
    if (result.labels().masked().contains(column)) {
      throw new Mismatch(
          "column " + column + " of " + result.evidence() + " is masked: it cannot be cited");
    }
  }

  /**
   * Why {@code cited} is not the best {@code cited.size()} rows of {@code result} by {@code
   * column}, in order; null when it is. Ties at the cut may be either row.
   */
  private String rankConflict(Rows result, String column, List<String> cited, boolean top) {
    if (new HashSet<>(cited).size() < cited.size()) {
      return "a row is ranked twice";
    }
    Map<String, Comparable<Object>> values = rankValues(result, column);
    for (String handle : cited) {
      if (!values.containsKey(handle)) {
        return name(result, handle) + " has no " + column + " to rank by";
      }
    }
    // Below zero: the first ranks before the second.
    int direction = top ? -1 : 1;
    List<Comparable<Object>> sorted =
        values.values().stream().sorted((a, b) -> direction * a.compareTo(b)).toList();
    if (cited.size() > sorted.size()) {
      return result.evidence() + " holds only " + sorted.size() + " rows with " + column;
    }
    Comparable<Object> cut = sorted.get(cited.size() - 1);
    String rank = (top ? "top " : "bottom ") + cited.size() + " by " + column;
    for (int i = 1; i < cited.size(); i++) {
      if (direction * values.get(cited.get(i - 1)).compareTo(values.get(cited.get(i))) > 0) {
        return "the rows are out of order: "
            + name(result, cited.get(i))
            + " ranks before "
            + name(result, cited.get(i - 1));
      }
    }
    for (Map.Entry<String, Comparable<Object>> row : values.entrySet()) {
      if (!cited.contains(row.getKey()) && direction * row.getValue().compareTo(cut) < 0) {
        return name(result, row.getKey()) + " belongs in the " + rank + " and is missing";
      }
    }
    for (String handle : cited) {
      if (direction * values.get(handle).compareTo(cut) > 0) {
        return name(result, handle) + " is not in the " + rank;
      }
    }
    return null;
  }

  private List<String> best(Rows result, String column, boolean max) {
    Map<String, Comparable<Object>> values = rankValues(result, column);
    Comparable<Object> best =
        values.values().stream()
            .reduce((a, b) -> (a.compareTo(b) >= 0) == max ? a : b)
            .orElse(null);
    return values.entrySet().stream()
        .filter(e -> e.getValue().compareTo(best) == 0)
        .map(Map.Entry::getKey)
        .toList();
  }

  /**
   * Numbers by value, text (ISO dates) by its order. A column holding both cannot be ranked, nor
   * one whose rows are in different units: 28,000 GBP is not below 78,000 EUR.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Comparable<Object>> rankValues(Rows result, String column) {
    Map<String, Comparable<Object>> values = new LinkedHashMap<>();
    Set<Class<?>> kinds = new HashSet<>();
    Set<String> rowUnits = new TreeSet<>();
    String unitColumn = result.labels().unitColumns().get(column);
    result
        .byHandle()
        .forEach(
            (handle, row) -> {
              Object value = row.get(column);
              Object comparable =
                  value instanceof Number
                      ? number(value)
                      : value instanceof String text ? text : null;
              if (comparable != null) {
                kinds.add(comparable.getClass());
                values.put(handle, (Comparable<Object>) comparable);
                if (unitColumn != null && row.get(unitColumn) != null) {
                  rowUnits.add(String.valueOf(row.get(unitColumn)));
                }
              }
            });
    if (kinds.size() > 1) {
      throw new Mismatch(
          "column "
              + column
              + " of "
              + result.evidence()
              + " holds numbers and text: it cannot be ranked");
    }
    if (rowUnits.size() > 1) {
      throw new Mismatch(
          column
              + " of "
              + result.evidence()
              + " is in "
              + String.join(" and ", rowUnits)
              + ": its rows cannot be ranked");
    }
    return values;
  }

  // ── quotes ──

  private Quote quote(QuoteRef ref) {
    Passages document = passages.get(ref.evidence());
    String reason = null;
    Passage passage = null;
    if (document == null) {
      reason =
          rows.containsKey(ref.evidence())
              ? ref.evidence() + " is a tool result, not a document"
              : "there is no document " + ref.evidence() + " in this exchange";
    } else if (ref.index() == null || ref.index() < 1 || ref.index() > document.passages().size()) {
      reason = ref.evidence() + " has no passage " + ref.index();
    } else {
      passage = document.passages().get(ref.index() - 1);
      if (ref.text() == null || ref.text().isBlank()) {
        reason = "the quote is empty";
      } else if (!spaces(passage.body()).contains(spaces(ref.text()))) {
        reason = "these words are not in " + ref.evidence() + ", passage " + ref.index();
      }
    }
    return new Quote(
        ref.id(),
        reason == null ? "quoted" : MISMATCH,
        ref.evidence(),
        ref.index(),
        passage == null ? null : passage.title(),
        passage == null ? null : passage.section(),
        ref.text(),
        reason);
  }

  // ── cells ──

  /** A cell, its value written as the user sees it, and its labels. */
  private record Resolved(
      Rows rows,
      String handle,
      Map<String, Object> row,
      String column,
      String unit,
      Integer decimals,
      String display,
      String value,
      BigDecimal number,
      Source source) {

    String where() {
      return rows.evidence() + " " + handle + " " + column;
    }

    /** The cell as one of several: its value travels with its source. */
    Source operand() {
      return new Source(
          source.evidence(),
          source.entity(),
          source.column(),
          source.rowKey(),
          source.subject(),
          source.period(),
          source.unit(),
          value,
          display);
    }
  }

  private Resolved resolve(AnswerFormat.Cell cell) {
    if (cell == null || cell.evidence() == null || cell.row() == null || cell.column() == null) {
      throw new Mismatch("a cell needs an evidence id, a row and a column");
    }
    Rows result = rowsOf(cell.evidence());
    Map<String, Object> row = rowOf(result, cell.row());
    String column = cell.column();
    // Masked values arrive as stars; a hidden column does not arrive at all.
    if (result.labels().masked().contains(column) || "****".equals(row.get(column))) {
      throw new Mismatch(
          "column " + column + " of " + cell.evidence() + " is masked: it cannot be cited");
    }
    if (!result.columns().contains(column)) {
      throw new Mismatch(cell.evidence() + " (" + result.entity() + ") has no column " + column);
    }
    ToolEvidence.Labels labels = result.labels();
    Object value = row.get(column);
    String unit = labels.units().get(column);
    if (unit == null && labels.unitColumns().containsKey(column)) {
      unit = text(row.get(labels.unitColumns().get(column)));
    }
    BigDecimal number = number(value);
    boolean grouped =
        !column.equals(labels.rowKey())
            && !column.equals(labels.subject())
            && !column.equals(labels.period());
    Integer decimals = labels.decimals().get(column);
    // A group's average arrives with every decimal the database computed.
    boolean average = "avg".equals(labels.function()) && !column.equals(labels.rowKey());
    String display =
        value == null
            ? "—"
            : number != null
                ? withUnit(group(shown(number, decimals, average), grouped), unit)
                : String.valueOf(value);
    Source source =
        new Source(
            cell.evidence(),
            result.entity(),
            column,
            text(row.get(labels.rowKey())),
            text(row.get(labels.subject())),
            text(row.get(labels.period())),
            number != null ? unit : null,
            null,
            null);
    return new Resolved(
        result,
        cell.row(),
        row,
        column,
        number != null ? unit : null,
        decimals,
        display,
        number != null ? plain(number) : text(value),
        number,
        source);
  }

  private Rows rowsOf(String evidence) {
    Rows result = rows.get(evidence);
    if (result == null) {
      throw new Mismatch(
          passages.containsKey(evidence)
              ? evidence + " is a document, not a tool result: quote it"
              : "there is no evidence " + evidence + " in this exchange");
    }
    return result;
  }

  private Map<String, Object> rowOf(Rows result, String handle) {
    Map<String, Object> row = result.byHandle().get(handle);
    if (row == null) {
      String owner = handles.get(handle);
      throw new Mismatch(
          owner != null
              ? handle + " is a row of " + owner + ", not of " + result.evidence()
              : result.evidence() + " has no row " + handle);
    }
    return row;
  }

  // ── claims ──

  /**
   * Where the model's claim of what a figure is about disagrees with the rows it rests on; null
   * when it does not. A label the rows do not carry cannot disagree.
   */
  private static String conflict(
      About about, List<Resolved> rows, String unit, List<String> columns) {
    String claimed = about == null ? null : about.subject();
    boolean self = claimed != null && SELF.contains(claimed.strip().toLowerCase(Locale.ROOT));
    for (Resolved row : rows) {
      boolean own = Boolean.TRUE.equals(row.row().get("self"));
      if (self && !own) {
        return "the row is not the asking user's own";
      }
      // Beside the user's own row, a figure from another row must say whom it is about.
      if (claimed == null
          && !own
          && row.rows().byHandle().values().stream()
              .anyMatch(r -> Boolean.TRUE.equals(r.get("self")))) {
        return "the row is not the asking user's own, and the figure does not say whom it is about";
      }
      String subject = row.source().subject();
      String function = row.rows().labels().function();
      // A group's value is about no one: an average is not one patient's reading.
      if (claimed != null && !self && subject == null && function != null) {
        return "the row is the "
            + function
            + " of a group"
            + (row.source().rowKey() == null ? "" : " (" + row.source().rowKey() + ")")
            + ", not about "
            + claimed.strip();
      }
      if (claimed != null && !self && subject != null && !sameName(claimed, subject)) {
        return "the row is about " + subject + ", not " + claimed.strip();
      }
    }
    if (about == null) {
      return null;
    }
    if (about.period() != null) {
      List<String> periods =
          rows.stream().map(r -> r.source().period()).filter(Objects::nonNull).toList();
      if (!periods.isEmpty()
          && periods.stream().noneMatch(period -> samePeriod(about.period(), period))) {
        return "the row is about " + String.join(", ", periods) + ", not " + about.period().strip();
      }
    }
    if (about.concept() != null
        && rows.stream().noneMatch(row -> sameConcept(about.concept(), row))) {
      return "the figure is " + String.join(", ", columns) + ", not " + about.concept().strip();
    }
    if (about.unit() != null && unit != null && !sameUnit(about.unit(), unit)) {
      return "the figure is in " + unit + ", not " + about.unit().strip();
    }
    return null;
  }

  /**
   * The column's name, or, where each row holds one figure of a named concept (a reported figure:
   * concept, period, value), a text of the row holding every word of the claim.
   */
  private static boolean sameConcept(String claimed, Resolved row) {
    if (sameWords(claimed, row.column())) {
      return true;
    }
    Set<String> claim = words(claimed);
    return !claim.isEmpty()
        && row.row().values().stream()
            .filter(String.class::isInstance)
            .anyMatch(text -> words((String) text).containsAll(claim));
  }

  /**
   * The words around a placeholder may not change its figure: no sign before it, no scale word and
   * no other unit after it. A repeat of its own unit is dropped, so it is not written twice.
   *
   * @param around the text before and after; the repeat is removed from it
   */
  private String besideFigure(String[] around, String status, String unit) {
    if (MISMATCH.equals(status) && unit == null) {
      return null;
    }
    if (SIGN_BEFORE.matcher(around[0]).find()) {
      return "the text puts a sign before it";
    }
    Matcher before = UNIT_BEFORE.matcher(around[0]);
    if (before.find() && unit != null && isUnit(before.group(1))) {
      if (!sameUnit(before.group(1), unit)) {
        return "the text calls it " + before.group(1) + ", the row says " + unit;
      }
      around[0] = around[0].substring(0, before.start());
    }
    Matcher after = WORD_AFTER.matcher(around[1]);
    if (after.find()) {
      String word = after.group(1);
      if (unit != null && sameUnit(word, unit)) {
        around[1] = around[1].substring(after.end());
      } else if (SCALES.contains(word.toLowerCase(Locale.ROOT))) {
        return "the text scales it: " + word;
      } else if (word.equals("%")) {
        return "the text makes it a percentage";
      } else if (unit != null && isUnit(word)) {
        return "the text calls it " + word + ", the row says " + unit;
      }
    }
    return null;
  }

  private boolean isUnit(String word) {
    return CURRENCIES.contains(word)
        || SYMBOLS.containsKey(word.toLowerCase(Locale.ROOT))
        || units.stream().anyMatch(unit -> sameUnit(unit, word));
  }

  private static Figure mismatch(Figure figure, String reason) {
    return new Figure(
        figure.id(), figure.display(), null, MISMATCH, figure.source(), figure.computed(), reason);
  }

  // ── rendering ──

  /** The segments and figures of one answer, in the order the text places them. */
  private final class Render {
    private final List<Segment> segments = new ArrayList<>();
    private final Map<String, Figure> figures = new LinkedHashMap<>();
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final Set<String> taken = new HashSet<>();
    private final StringBuilder answer = new StringBuilder();
    private int unsourced;

    Render(Set<String> figureIds, Set<String> quoteIds) {
      taken.addAll(figureIds);
      taken.addAll(quoteIds);
    }

    /**
     * Text as written; with {@code scan}, every fact value and every number in it not in the
     * question is unsourced. Facts first: "5 mg" of a dose is one value, not the number 5.
     */
    void text(String text, boolean scan) {
      Matcher fact = factPattern == null ? null : factPattern.matcher(text);
      int at = 0;
      while (scan && fact != null && fact.find()) {
        String key = factKey(fact.group());
        String where = facts.get(key);
        // null: a letter the pattern's case folding matches and lower-casing does not (ſ for s)
        if (where == null || asked(key)) {
          continue;
        }
        numbers(text.substring(at, fact.start()), scan);
        unsourced(
            fact.group(), "typed in the text, not referenced; " + where + " holds the same value");
        at = fact.end();
      }
      numbers(text.substring(at), scan);
    }

    private void numbers(String text, boolean scan) {
      Matcher number = NUMBER.matcher(text);
      int at = 0;
      while (scan && number.find()) {
        int start = number.start();
        if (exempt(number.group())
            || listMarker(answer + text.substring(at, start), text, number.end(), number.group())) {
          continue;
        }
        if (start > 0
            && "-−".indexOf(text.charAt(start - 1)) >= 0
            && (start == 1 || !Character.isLetterOrDigit(text.charAt(start - 2)))) {
          start--;
        }
        plain(text.substring(at, start));
        unsourced(text.substring(start, number.end()), unsourcedReason(number.group()));
        at = number.end();
      }
      plain(text.substring(at));
    }

    private void unsourced(String display, String reason) {
      String id;
      do {
        id = "u" + ++unsourced;
      } while (taken.contains(id));
      figure(new Figure(id, display, null, UNSOURCED, null, null, reason));
    }

    void figure(Figure figure) {
      figures.putIfAbsent(figure.id(), figure);
      segments.add(new Segment(null, figure.id(), null));
      answer.append(figure.display());
    }

    void table(Table table) {
      tables.putIfAbsent(table.id(), table);
      segments.add(new Segment(null, null, table.id()));
      if (!answer.isEmpty() && answer.charAt(answer.length() - 1) != '\n') {
        answer.append('\n');
      }
      answer.append(table.display()).append('\n');
    }

    private void plain(String text) {
      if (text.isEmpty()) {
        return;
      }
      Segment last = segments.isEmpty() ? null : segments.getLast();
      if (last != null && last.text() != null) {
        segments.set(segments.size() - 1, new Segment(last.text() + text, null, null));
      } else {
        segments.add(new Segment(text, null, null));
      }
      answer.append(text);
    }

    GroundedAnswer done(List<Quote> quotes) {
      List<Figure> placed = List.copyOf(figures.values());
      List<Table> shown = List.copyOf(tables.values());
      return new GroundedAnswer(
          answer.toString(),
          List.copyOf(segments),
          placed,
          shown,
          quotes,
          GroundedAnswer.Provenance.of(placed, shown));
    }
  }

  /**
   * The question names this fact value: the key is a whole phrase of it, with a fact's edges
   * (insulinoma does not name insulin, 25 mg does not name 5 mg).
   */
  private boolean asked(String key) {
    // The substring test first spares compiling a pattern for every value the question lacks.
    return questionKey.contains(key)
        && Pattern.compile(FACT_BEFORE + Pattern.quote(key) + FACT_AFTER)
            .matcher(questionKey)
            .find();
  }

  /** An evidence id, or a number the question holds, is not a figure. */
  private boolean exempt(String token) {
    return EVIDENCE_ID.matcher(token).matches() || questionTokens.contains(numberKey(token));
  }

  /** A list item's number: alone at the start of its line, then {@code .} or {@code )}. */
  private static boolean listMarker(String before, String text, int end, String token) {
    String line = before.substring(before.lastIndexOf('\n') + 1);
    return line.isBlank()
        && token.chars().allMatch(Character::isDigit)
        && end + 1 < text.length()
        && ".)".indexOf(text.charAt(end)) >= 0
        && Character.isWhitespace(text.charAt(end + 1));
  }

  /** What a typed number matches, so the reader sees where the model may have read it. */
  private String unsourcedReason(String token) {
    BigDecimal typed;
    try {
      typed = new BigDecimal(token.replace(",", ""));
    } catch (NumberFormatException notANumber) {
      return "typed in the text, not referenced";
    }
    for (Rows result : rows.values()) {
      for (Map.Entry<String, Map<String, Object>> row : result.byHandle().entrySet()) {
        for (String column : result.columns()) {
          BigDecimal held = number(row.getValue().get(column));
          if (held != null
              && held.compareTo(typed) == 0
              && !result.labels().masked().contains(column)) {
            return "typed in the text, not referenced; "
                + result.evidence()
                + " "
                + row.getKey()
                + " "
                + column
                + " holds the same value";
          }
        }
      }
    }
    return "typed in the text, not referenced";
  }

  // ── values ──

  static BigDecimal number(Object value) {
    return value instanceof Number n
            && !(value instanceof Double d && (d.isNaN() || d.isInfinite()))
        ? new BigDecimal(n.toString())
        : null;
  }

  private static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
    if (divisor.signum() == 0) {
      throw new Mismatch("it divides by zero");
    }
    return dividend.divide(divisor, EXACT);
  }

  /** The value as digits, without trailing zeros: 78000.00 is 78000. */
  static String plain(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
  }

  /**
   * What a figure shows; {@code value} keeps the exact digits. Rounded to the column's declared
   * decimals, which then all show; else an average to two, its trailing zeros dropped (119.25,
   * 15.1); else as held.
   */
  private static BigDecimal shown(BigDecimal number, Integer decimals, boolean average) {
    return decimals != null
        ? number.setScale(decimals, RoundingMode.HALF_UP)
        : average ? number.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros() : number;
  }

  /** 78000.00 as 78,000; 1234.50 as 1,234.50: zero cents go, cents stay. */
  private static String group(BigDecimal value, boolean grouped) {
    int scale = value.stripTrailingZeros().scale() <= 0 ? 0 : value.scale();
    DecimalFormat format =
        new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
    format.setGroupingUsed(grouped);
    format.setMinimumFractionDigits(scale);
    format.setMaximumFractionDigits(scale);
    return format.format(value.setScale(scale, RoundingMode.HALF_UP));
  }

  private static String withUnit(String number, String unit) {
    return unit == null ? number : unit.equals("%") ? number + "%" : number + " " + unit;
  }

  private static String text(Object value) {
    return value == null
        ? null
        : value instanceof Number ? plain(number(value)) : String.valueOf(value);
  }

  private static String name(Source source) {
    return source.subject() != null
        ? source.subject()
        : source.rowKey() != null ? source.rowKey() : source.display();
  }

  private static String name(Rows result, String handle) {
    Map<String, Object> row = result.byHandle().get(handle);
    String subject = row == null ? null : text(row.get(result.labels().subject()));
    return subject == null ? handle : subject + " (" + handle + ")";
  }

  private static String cellText(String text) {
    return text.replace("|", "\\|").replace('\n', ' ');
  }

  // ── comparing claims ──

  private static boolean sameName(String claimed, String label) {
    return sameWords(claimed.replaceAll("['’]s\\b", ""), label);
  }

  private static boolean sameWords(String claimed, String column) {
    Set<String> claim = words(claimed);
    Set<String> held = words(column);
    return !claim.isEmpty() && (held.containsAll(claim) || claim.containsAll(held));
  }

  /**
   * 2026 is 2026-03-14 and FY2026; 2026-04 is not 2026-03-14. A period in words is compared by its
   * years alone.
   */
  private static boolean samePeriod(String claimed, String label) {
    String claim = claimed.strip().toLowerCase(Locale.ROOT);
    String held = label.strip().toLowerCase(Locale.ROOT);
    if (claim.isEmpty() || held.startsWith(claim) || claim.startsWith(held)) {
      return true;
    }
    if (ISO_LIKE.matcher(claim).matches() && ISO_LIKE.matcher(held).matches()) {
      return false;
    }
    Set<String> claimYears =
        YEAR.matcher(claim).results().map(m -> m.group()).collect(Collectors.toSet());
    Set<String> heldYears =
        YEAR.matcher(held).results().map(m -> m.group()).collect(Collectors.toSet());
    return claimYears.isEmpty()
        || heldYears.isEmpty()
        || claimYears.stream().anyMatch(heldYears::contains);
  }

  private static boolean sameUnit(String a, String b) {
    return canonicalUnit(a).equals(canonicalUnit(b));
  }

  private static String canonicalUnit(String unit) {
    String lower = unit.strip().toLowerCase(Locale.ROOT);
    String mapped =
        SYMBOLS
            .getOrDefault(lower, SYMBOLS.getOrDefault(unit.strip(), unit.strip()))
            .toLowerCase(Locale.ROOT);
    return mapped.length() > 3 && mapped.endsWith("s")
        ? mapped.substring(0, mapped.length() - 1)
        : mapped;
  }

  private static Set<String> words(String text) {
    String plain =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
    return Arrays.stream(plain.split("[^\\p{L}\\p{N}]+"))
        .filter(word -> !word.isEmpty())
        .collect(Collectors.toSet());
  }

  private static String spaces(String text) {
    return text.strip().replaceAll("\\s+", " ");
  }

  /** A number as the question and the answer may both write it: 70,000 is 70000. */
  private static String numberKey(String token) {
    return token.toLowerCase(Locale.ROOT).replace(",", "");
  }

  /** A fact value as the row, question and answer may write it: TWICE Daily is twice daily. */
  private static String factKey(String text) {
    return spaces(text).toLowerCase(Locale.ROOT);
  }

  /**
   * Any of {@code keys} as typed: in any case, any spaces between its words, never inside a longer
   * word or a number; the longest first, so "insulin glargine" is not taken for "insulin".
   */
  private static Pattern factPattern(Collection<String> keys) {
    String alternatives =
        keys.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(
                key ->
                    Arrays.stream(key.split(" "))
                        .map(Pattern::quote)
                        .collect(Collectors.joining("\\s+")))
            .collect(Collectors.joining("|"));
    // UNICODE_CASE: a value in any alphabet, in any case; \s stays ASCII, as spaces() reads it.
    return Pattern.compile(
        FACT_BEFORE + "(?:" + alternatives + ")" + FACT_AFTER,
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  /** A reference that does not hold; the message says why, in words a reader understands. */
  private static final class Mismatch extends RuntimeException {
    Mismatch(String reason) {
      super(reason, null, false, false);
    }
  }
}
