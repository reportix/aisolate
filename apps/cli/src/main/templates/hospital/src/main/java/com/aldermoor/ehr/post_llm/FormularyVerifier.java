package com.aldermoor.ehr.post_llm;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.DataSources;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Json;
import com.reportix.aisolate.sdk.Verdict;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A drug the answer names in its own words is one the tools returned: the formulary, the drug names
 * of the whole health record, says which words are drugs, and a drug of it that the text names and
 * no medication list of this exchange holds fails the answer. A drug the question names is
 * expected, not reported: the answer may say that patient does not take it. A figure's or a table's
 * display is written in from a row and never scanned, nor are the words quoted from a clinical
 * note: they are the note's, not the model's. The one query reads drug names, never a row, and a
 * note only repeats the word the answer typed.
 */
@Description("Check the drugs named")
@DataSources("ehr")
public final class FormularyVerifier implements AnswerVerifier {
  @Override
  public int order() {
    return 2;
  }

  private static final String FORMULARY = "SELECT DISTINCT drug FROM ehr.medications";

  // Read on the first answer and kept: a drug added later is unknown until the next deploy. Two
  // first answers may both read it, the same names.
  private final AtomicReference<Set<String>> formulary = new AtomicReference<>();

  @Override
  public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
    if (answer.groundingJson().isEmpty()) {
      return Verdict.pass("no figure to check");
    }
    String typed = typed(object(Json.parse(answer.groundingJson())));
    String question = answer.question().toLowerCase(Locale.ROOT);
    Set<String> returned = returned(evidence);
    boolean named = false;
    for (String drug : formulary(aisolate)) {
      String lower = drug.toLowerCase(Locale.ROOT);
      // UNICODE_CHARACTER_CLASS: Java's \b is ASCII, so an accented letter would end a word.
      Pattern word =
          Pattern.compile("\\b" + Pattern.quote(lower) + "\\b", Pattern.UNICODE_CHARACTER_CLASS);
      // A drug the question names is the user's own word: "Is he on X?" is answered "no, not X".
      if (!word.matcher(typed).find() || word.matcher(question).find()) {
        continue;
      }
      named = true;
      if (!returned.contains(lower)) {
        return Verdict.fail("names a drug the returned records do not hold: " + drug);
      }
    }
    return Verdict.pass(
        named ? "every drug named is in the records" : "no drug named outside the records");
  }

  // What the model typed: the text pieces of the answer, less the words it quotes from a note as
  // the airlock writes them in, between double quotes, lower-cased. A quote leaves a space: it
  // joins no word. A quote that is not in its passage stays: those words are the model's own.
  private static String typed(Map<String, Object> grounding) {
    String typed =
        array(grounding.get("segments")).stream()
            .map(segment -> object(segment).get("text"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .collect(Collectors.joining(" "));
    // Longest first: a quote whose words hold another's form goes with it, not out of it.
    List<String> quoted =
        array(grounding.get("quotes")).stream()
            .map(FormularyVerifier::object)
            .filter(quote -> "quoted".equals(quote.get("status")))
            .map(quote -> quote.get("text"))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    for (String quote : quoted) {
      typed = typed.replace('"' + quote + '"', " ");
    }
    return typed.toLowerCase(Locale.ROOT);
  }

  // The drug of every row of every medication list a tool returned in this exchange, lower-cased.
  private static Set<String> returned(List<Evidence> evidence) {
    Set<String> drugs = new HashSet<>();
    for (Evidence each : evidence) {
      // A tool result is a JSON object, the evidence of passages a list.
      if (each.contentJson().startsWith("{")) {
        Map<String, Object> result = object(Json.parse(each.contentJson()));
        if ("ehr.medications".equals(result.get("entity"))) {
          for (Object row : array(result.get("rows"))) {
            if (object(row).get("drug") instanceof String drug) {
              drugs.add(drug.toLowerCase(Locale.ROOT));
            }
          }
        }
      }
    }
    return drugs;
  }

  private Set<String> formulary(AIsolateManager aisolate) {
    Set<String> known = formulary.get();
    if (known == null) {
      known = read(aisolate);
      formulary.compareAndSet(null, known);
    }
    return known;
  }

  // Sorted: the first drug the answer names that no list holds is the one the note names, whatever
  // order the database answers in.
  private static Set<String> read(AIsolateManager aisolate) {
    Set<String> drugs = new TreeSet<>();
    try (Connection db = aisolate.dataSource("ehr").getConnection();
        PreparedStatement query = db.prepareStatement(FORMULARY);
        ResultSet rows = query.executeQuery()) {
      while (rows.next()) {
        String drug = rows.getString(1);
        // A blank name would be a word between any two words.
        if (drug != null && !drug.isBlank()) {
          drugs.add(drug);
        }
      }
    } catch (SQLException unreadable) {
      // The airlock turns it into a WARN; its message can echo the database's address.
      throw new IllegalStateException("the formulary could not be read", unreadable);
    }
    return drugs;
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
}
