package com.aldermoor.ehr.post_llm;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Json;
import com.reportix.aisolate.sdk.Verdict;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every figure the answer cites is about a patient the question names: Amira's blood pressure does
 * not answer what Julian's was. A patient is a value of a tool result's subject column, and the
 * question names one when the first word of the name is a word of the question. A question naming
 * none, or an answer citing no figure of a patient, has nothing to check.
 */
@Description("Check the patient asked")
public final class PatientVerifier implements AnswerVerifier {
  @Override
  public int order() {
    return 3;
  }

  // A name's first word and the question's words: letters only, three or more.
  private static final Pattern WORD = Pattern.compile("\\p{L}{3,}");

  // The patient a cited source is about, with the display the answer shows it under.
  private record Cited(Object display, String subject) {}

  @Override
  public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
    if (answer.groundingJson().isEmpty()) {
      return Verdict.pass("no figure to check");
    }
    // A tool result is a JSON object, the evidence of passages a list.
    Map<String, Map<String, Object>> results = new HashMap<>();
    for (Evidence each : evidence) {
      if (each.contentJson().startsWith("{")) {
        results.put(each.id(), object(Json.parse(each.contentJson())));
      }
    }
    Set<String> words =
        WORD.matcher(answer.question().toLowerCase(Locale.ROOT))
            .results()
            .map(MatchResult::group)
            .collect(Collectors.toSet());
    Set<String> asked = new TreeSet<>();
    for (String patient : patients(results)) {
      Matcher first = WORD.matcher(patient.toLowerCase(Locale.ROOT));
      if (first.find() && words.contains(first.group())) {
        asked.add(patient);
      }
    }
    if (asked.isEmpty()) {
      return Verdict.pass("the question names no patient");
    }
    List<Cited> cited = sources(object(Json.parse(answer.groundingJson())), results);
    if (cited.isEmpty()) {
      return Verdict.pass("no figure to check");
    }
    String names = String.join(", ", asked);
    for (Cited source : cited) {
      if (!asked.contains(source.subject())) {
        return Verdict.fail(
            "the figure "
                + source.display()
                + " is about "
                + source.subject()
                + "; the question asks about "
                + names);
      }
    }
    return Verdict.pass("every figure is about " + names);
  }

  // The subject-column values of every tool result.
  private static List<String> patients(Map<String, Map<String, Object>> results) {
    List<String> patients = new ArrayList<>();
    for (Map<String, Object> result : results.values()) {
      // labels are null for a result a tool built itself
      if (object(result.get("labels")).get("subject") instanceof String column) {
        for (Object row : array(result.get("rows"))) {
          if (object(row).get(column) instanceof String name) {
            patients.add(name);
          }
        }
      }
    }
    return patients;
  }

  // Each source the answer cites that names a subject, with the display it is shown under: a
  // figure's, its computation's operands, ranked rows and row, a table cell's. Whatever the status:
  // a mismatch keeps its source and can be about another patient all the same.
  private static List<Cited> sources(
      Map<String, Object> grounding, Map<String, Map<String, Object>> results) {
    List<Cited> sources = new ArrayList<>();
    for (Object item : array(grounding.get("figures"))) {
      Map<String, Object> figure = object(item);
      Map<String, Object> computed = object(figure.get("computed"));
      cite(sources, results, figure.get("display"), figure.get("source"));
      for (Object operand : array(computed.get("of"))) {
        cite(sources, results, object(operand).get("display"), operand);
      }
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

  // A null in the grounding is left out, so a source may be missing, as an unsourced figure's is,
  // and its subject may be: such a source is about no patient. Only a tool result of this exchange
  // says whose row a source is.
  private static void cite(
      List<Cited> sources,
      Map<String, Map<String, Object>> results,
      Object display,
      Object source) {
    Map<String, Object> cited = object(source);
    if (cited.get("subject") instanceof String subject
        && results.containsKey(cited.get("evidence"))) {
      sources.add(new Cited(display, subject));
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
