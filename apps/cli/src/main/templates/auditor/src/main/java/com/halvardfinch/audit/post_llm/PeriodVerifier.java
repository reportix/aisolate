package com.halvardfinch.audit.post_llm;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Json;
import com.reportix.aisolate.sdk.Verdict;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every figure the answer cites is for a year the question names: 2024's revenue does not answer
 * what the company reported for 2025. A period is read by its year, the first in it: "FY2025" and
 * "2025-10-03" are both 2025. A computation's cells are not judged, and the years they span are
 * expected too: a growth in 2025 is shown from the 2024 cell to the 2025 one. A question naming no
 * year (an amount such as "2000 EUR" is no year), or an answer citing no period with one, has
 * nothing to check.
 */
@Description("Check the period asked")
public final class PeriodVerifier implements AnswerVerifier {
  @Override
  public int order() {
    return 1;
  }

  // A year of the 1900s or 2000s: four digits that are not part of a longer number.
  private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19|20)\\d\\d(?!\\d)");

  // A year the question names: "2000 EUR", "2000%" and "$2000" (or with the euro sign) are amounts,
  // not years; "2025 European" is a year.
  private static final Pattern ASKED =
      Pattern.compile(
          "(?<![\\d\\u20AC$])(19|20)\\d\\d(?!\\d)(?!\\s*(?:(?:eur|usd)\\b|\\u20AC|%|\\$))",
          Pattern.CASE_INSENSITIVE);

  // The period a cited source is for and the year it is read as, with the display the answer shows
  // it under.
  private record Cited(Object display, String period, String year) {}

  @Override
  public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
    if (answer.groundingJson().isEmpty()) {
      return Verdict.pass("no figure to check");
    }
    Set<String> asked =
        ASKED
            .matcher(answer.question())
            .results()
            .map(MatchResult::group)
            .collect(Collectors.toCollection(TreeSet::new));
    if (asked.isEmpty()) {
      return Verdict.pass("the question names no period");
    }
    // A tool result is a JSON object, the evidence of passages a list.
    Set<String> results = new HashSet<>();
    for (Evidence item : evidence) {
      if (item.contentJson().startsWith("{")) {
        results.add(item.id());
      }
    }
    Map<String, Object> grounding = object(Json.parse(answer.groundingJson()));
    List<Cited> cited = sources(grounding, results);
    if (cited.isEmpty()) {
      return Verdict.pass("no figure to check");
    }
    Set<String> expected = new HashSet<>(asked);
    expected.addAll(spanned(grounding, results));
    String years = String.join(", ", asked);
    for (Cited source : cited) {
      if (!expected.contains(source.year())) {
        return Verdict.fail(
            "the figure "
                + source.display()
                + " is for "
                + source.period()
                + "; the question asks about "
                + years);
      }
    }
    return Verdict.pass("every figure is for " + years);
  }

  // Each source the answer cites that is for a period with a year, with the display it is shown
  // under: a figure's, its computation's ranked rows and row, a table cell's; not its operands, a
  // growth or a difference is across periods. Whatever the status: a mismatch keeps its source and
  // can be for another year all the same.
  private static List<Cited> sources(Map<String, Object> grounding, Set<String> results) {
    List<Cited> sources = new ArrayList<>();
    for (Object item : array(grounding.get("figures"))) {
      Map<String, Object> figure = object(item);
      Map<String, Object> computed = object(figure.get("computed"));
      cite(sources, results, figure.get("display"), figure.get("source"));
      for (Object ranked : array(computed.get("rows"))) {
        cite(sources, results, object(ranked).get("display"), ranked);
      }
      cite(sources, results, figure.get("display"), computed.get("row"));
    }
    for (Object table : array(grounding.get("tables"))) {
      for (Object row : array(object(table).get("rows"))) {
        for (Object item : array(row)) {
          Map<String, Object> cell = object(item);
          cite(sources, results, cell.get("display"), cell.get("source"));
        }
      }
    }
    return sources;
  }

  // The years of the cells the computations of the answer are made of: a growth in 2025 is shown
  // from the 2024 cell to the 2025 one, so the answer may show both.
  private static Set<String> spanned(Map<String, Object> grounding, Set<String> results) {
    List<Cited> operands = new ArrayList<>();
    for (Object item : array(grounding.get("figures"))) {
      for (Object operand : array(object(object(item).get("computed")).get("of"))) {
        cite(operands, results, object(operand).get("display"), operand);
      }
    }
    return operands.stream().map(Cited::year).collect(Collectors.toSet());
  }

  // A null in the grounding is left out, so a source may be missing, as an unsourced figure's is,
  // and its period may be: such a source is for no year. A source of no tool result of this
  // exchange is not counted.
  private static void cite(
      List<Cited> sources, Set<String> results, Object display, Object source) {
    Map<String, Object> cited = object(source);
    if (results.contains(cited.get("evidence")) && cited.get("period") instanceof String period) {
      Matcher year = YEAR.matcher(period);
      if (year.find()) {
        sources.add(new Cited(display, period, year.group()));
      }
    }
  }

  // Json.parse gives a Map for an object and a List for an array; anything else reads as empty.
  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object json) {
    return json instanceof Map<?, ?> ? (Map<String, Object>) json : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object json) {
    return json instanceof List<?> ? (List<Object>) json : List.of();
  }
}
