package com.acme.sales.post_llm;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Json;
import com.reportix.aisolate.sdk.Verdict;
import java.util.ArrayList;
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
 * Every figure the answer cites is about a customer the question names: Northwind's invoice does
 * not answer what Fabrikam owes. A customer is a value of a tool result's subject column, and the
 * question names it when the first word of its name is a word of the question. A question naming
 * none, or an answer citing no figure of a customer, has nothing to check.
 */
@Description("Check the customer asked")
public final class CustomerVerifier implements AnswerVerifier {
  @Override
  public int order() {
    return 1;
  }

  // A name's first word and the question's words: letters only, three or more.
  private static final Pattern WORD = Pattern.compile("\\p{L}{3,}");

  // The customer a cited source is about, with the display the answer shows it under.
  private record Cited(Object display, String subject) {}

  @Override
  public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
    if (answer.groundingJson().isEmpty()) {
      return Verdict.pass("no figure to check");
    }
    Set<String> words =
        WORD.matcher(answer.question().toLowerCase(Locale.ROOT))
            .results()
            .map(MatchResult::group)
            .collect(Collectors.toSet());
    Set<String> asked = new TreeSet<>();
    for (String customer : customers(evidence)) {
      Matcher first = WORD.matcher(customer.toLowerCase(Locale.ROOT));
      if (first.find() && words.contains(first.group())) {
        asked.add(customer);
      }
    }
    if (asked.isEmpty()) {
      return Verdict.pass("the question names no customer");
    }
    List<Cited> cited = sources(object(Json.parse(answer.groundingJson())));
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

  // The subject-column values of every tool result; passages are a JSON list, no result.
  private static List<String> customers(List<Evidence> evidence) {
    List<String> customers = new ArrayList<>();
    for (Evidence item : evidence) {
      if (item.contentJson().startsWith("{")) {
        Map<String, Object> result = object(Json.parse(item.contentJson()));
        // labels are null for a result a tool built itself
        if (object(result.get("labels")).get("subject") instanceof String column) {
          for (Object row : array(result.get("rows"))) {
            if (object(row).get(column) instanceof String name) {
              customers.add(name);
            }
          }
        }
      }
    }
    return customers;
  }

  // Each source the answer cites that names a subject, with the display it is shown under: a
  // figure's, its computation's operands, ranked rows and row, a table cell's. Whatever the status:
  // a mismatch keeps its source and can be about another customer all the same.
  private static List<Cited> sources(Map<String, Object> grounding) {
    List<Cited> sources = new ArrayList<>();
    for (Object item : array(grounding.get("figures"))) {
      Map<String, Object> figure = object(item);
      Map<String, Object> computed = object(figure.get("computed"));
      cite(sources, figure.get("display"), figure.get("source"));
      for (Object operand : array(computed.get("of"))) {
        cite(sources, object(operand).get("display"), operand);
      }
      for (Object ranked : array(computed.get("rows"))) {
        cite(sources, object(ranked).get("display"), ranked);
      }
      cite(sources, figure.get("display"), computed.get("row"));
    }
    for (Object table : array(grounding.get("tables"))) {
      for (Object row : array(object(table).get("rows"))) {
        for (Object item : array(row)) {
          Map<String, Object> cell = object(item);
          cite(sources, cell.get("display"), cell.get("source"));
        }
      }
    }
    return sources;
  }

  // A null in the grounding is left out, so a source may be missing, as an unsourced figure's is,
  // and its subject may be: such a source is about no customer.
  private static void cite(List<Cited> sources, Object display, Object source) {
    if (object(source).get("subject") instanceof String subject) {
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
