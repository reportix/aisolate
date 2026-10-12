package com.aldermoor.ehr.post_llm;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Json;
import com.reportix.aisolate.sdk.Verdict;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A question for the latest reading (last, latest, most recent, current; "last" as a date, as in
 * "last Thursday", is no such question) is answered with the latest one: for each series of
 * readings the answer cites, it cites the series' latest row. A series is one patient's readings of
 * one kind in one tool result: a lab test, the vitals, the ECGs, the sessions. Earlier rows may
 * come with the latest ("went from ... to ..."); a series cited by earlier rows alone fails the
 * answer. Only a cell cited as a figure is judged: a table lists readings and claims none is the
 * latest. Reads the grounding AIsolate wrote and the tool results of the exchange; no model, no
 * state kept between exchanges, so one instance serves every exchange concurrently.
 */
@Description("Check the latest reading")
public final class LatestReadingVerifier implements AnswerVerifier {
  @Override
  public int order() {
    return 1;
  }

  // "last Thursday", "last night" and "at last" are dates and a sigh, no ask for the latest.
  // UNICODE_CHARACTER_CLASS: Java's \b is ASCII, so an accented letter would end a word.
  private static final Pattern LATEST =
      Pattern.compile(
          "\\b(?:(?<!\\bat )last(?!\\s+(?:night|evening|morning|afternoon|week|month|year"
              + "|monday|tuesday|wednesday|thursday|friday|saturday|sunday))"
              + "|latest|most\\s+recent|current)\\b",
          Pattern.UNICODE_CHARACTER_CLASS);

  // The entities whose rows are readings, each with the column that tells two readings of one
  // patient apart: Troponin T is not Potassium. A medication or a diagnosis is no reading: what
  // holds now is not the last one recorded.
  private static final Map<String, Optional<String>> ENTITIES =
      Map.of(
          "cardiology.labs", Optional.of("test"),
          "cardiology.vitals", Optional.empty(),
          "cardiology.ecgs", Optional.empty(),
          "psychiatry.sessions", Optional.empty());

  // What a series is: one patient's readings, of one test for a lab, in one tool result.
  private record Series(Object evidence, Object subject, Object concept) {}

  /** A cited cell: its series, its period, what to call the series, and the series' latest. */
  private record Reading(Series series, String period, String name, String latest) {}

  @Override
  public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
    if (answer.groundingJson().isEmpty()) {
      return Verdict.pass("no figure to check");
    }
    if (!LATEST.matcher(answer.question().toLowerCase(Locale.ROOT)).find()) {
      return Verdict.pass("the question does not ask for the latest");
    }
    // A tool result is a JSON object, the evidence of passages a list.
    Map<String, Map<String, Object>> results = new HashMap<>();
    for (Evidence each : evidence) {
      if (each.contentJson().startsWith("{")) {
        results.put(each.id(), object(Json.parse(each.contentJson())));
      }
    }
    // The latest cell the answer cites of each series, in the order the answer first cites them.
    Map<Series, Reading> cited = new LinkedHashMap<>();
    for (Object figure : array(object(Json.parse(answer.groundingJson())).get("figures"))) {
      // A mismatch keeps its source, so a figure the grounding flagged is judged too.
      Reading reading = reading(object(object(figure).get("source")), results);
      if (reading != null) {
        cited.merge(
            reading.series(),
            reading,
            (kept, other) -> other.period().compareTo(kept.period()) > 0 ? other : kept);
      }
    }
    for (Reading reading : cited.values()) {
      if (reading.latest().compareTo(reading.period()) > 0) {
        return Verdict.fail(
            "the answer cites the "
                + reading.period()
                + " "
                + reading.name()
                + "; the latest in the records is "
                + reading.latest());
      }
    }
    return Verdict.pass(cited.isEmpty() ? "no figure to check" : "the latest reading is cited");
  }

  // Null when the source is no reading to judge: not a row of a tool result of this exchange, of an
  // entity that holds no readings, an aggregate, or a result without a period.
  private static Reading reading(
      Map<String, Object> source, Map<String, Map<String, Object>> results) {
    Map<String, Object> result = results.get(source.get("evidence"));
    if (result == null) {
      return null;
    }
    String entity = Objects.toString(result.get("entity"), "");
    Optional<String> conceptColumn = ENTITIES.get(entity);
    if (conceptColumn == null) {
      return null;
    }
    // labels are null for a result a tool built itself
    Map<String, Object> labels = object(result.get("labels"));
    String periodColumn = text(labels.get("period"));
    if (periodColumn == null || text(labels.get("function")) != null) {
      return null;
    }
    List<Map<String, Object>> rows =
        array(result.get("rows")).stream().map(LatestReadingVerifier::object).toList();
    String rowKey = text(labels.get("row_key"));
    Map<String, Object> cited =
        rows.stream()
            .filter(
                row ->
                    rowKey != null
                        && row.containsKey(rowKey)
                        && String.valueOf(row.get(rowKey)).equals(source.get("row_key")))
            .findFirst()
            .orElse(null);
    if (cited == null || !(cited.get(periodColumn) instanceof String period)) {
      return null;
    }
    String subjectColumn = text(labels.get("subject"));
    String conceptName = conceptColumn.orElse(null);
    String latest =
        rows.stream()
            .filter(row -> sameValue(row, cited, subjectColumn))
            .filter(row -> sameValue(row, cited, conceptName))
            .map(row -> row.get(periodColumn))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .max(Comparator.naturalOrder())
            .orElse(period);
    Object subject = subjectColumn == null ? null : cited.get(subjectColumn);
    Object concept = conceptName == null ? null : cited.get(conceptName);
    // The entity's last segment: cardiology.vitals, vitals
    String name =
        concept instanceof String test && !test.isEmpty()
            ? test
            : entity.substring(entity.lastIndexOf('.') + 1);
    return new Reading(new Series(source.get("evidence"), subject, concept), period, name, latest);
  }

  // No such column (the result has no subject, the entity no concept): nothing tells rows apart.
  private static boolean sameValue(
      Map<String, Object> row, Map<String, Object> cited, String column) {
    return column == null || Objects.equals(row.get(column), cited.get(column));
  }

  // Json.parse hands back maps and lists; anything else reads as empty.
  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object json) {
    return json instanceof Map ? (Map<String, Object>) json : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object json) {
    return json instanceof List ? (List<Object>) json : List.of();
  }

  private static String text(Object json) {
    return json instanceof String string && !string.isEmpty() ? string : null;
  }
}
