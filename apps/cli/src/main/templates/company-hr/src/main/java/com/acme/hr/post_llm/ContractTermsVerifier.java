package com.acme.hr.post_llm;

import com.acme.hr.pre_llm.ContractsContextPreparator;
import com.acme.hr.tools.ContractTools;
import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.DataSources;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Json;
import com.reportix.aisolate.sdk.Verdict;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every figure the answer cites from the HR system's terms (hr.contract_terms: salary, bonus,
 * weekly hours, vacation days) is what that employee's signed contract states: its sections and its
 * amendments, the last statement of a term being the current one. The contract is read from the
 * data source hr as filed, never from the passages of the exchange: retrieval ranks them by
 * likeness and may leave an amendment out. A figure no section states is a WARN, not a wrong
 * answer: an amendment not filed, or a number mistyped at onboarding. Two FAILs, for an answer
 * about someone else: a question or an answer that names an employee of whom no row and no passage
 * was read must be I don't know, whatever the question claims about who is asking (the asking
 * user's own row answers for nobody else, not as a yes or no either); and a figure AIsolate marked
 * a mismatch, the answer claiming it is about someone or something the row is not. Reads the
 * grounding AIsolate wrote; no model, no state kept between exchanges.
 */
@Description("Check terms against contract")
@DataSources("hr")
public final class ContractTermsVerifier implements AnswerVerifier {
  @Override
  public int order() {
    return 1;
  }

  // The terms a contract states in digits, each as its sections word it: the number is the first
  // after the term's word in the same sentence. A term in words ("three months") is not compared.
  private static final Map<String, Pattern> STATED =
      Map.of(
          "salary_eur", Pattern.compile("salary[^.]*? ([\\d,]+) EUR"),
          "bonus_eur", Pattern.compile("bonus of ([\\d,]+) EUR"),
          "weekly_hours", Pattern.compile("(\\d+) hours per week"),
          "vacation_days", Pattern.compile("(\\d+) vacation days"));
  private static final String TITLE = "Employment Contract ";
  // The sections as filed: an amendment comes after the section it changes.
  private static final String SECTIONS =
      "SELECT section, body FROM hr.documents WHERE context_preparator = ? AND title = ? ORDER BY"
          + " id";
  // Everyone with a contract: the names a question may drop that the asking user may not read.
  private static final String EMPLOYEES = "SELECT employee FROM hr.contract_terms";
  // The one answer allowed when the question is about someone the evidence does not hold.
  private static final String DONT_KNOW = "i don't know";

  private record Section(String name, String body) {}

  // The section that states a term and its number.
  private record Stated(String section, double number) {}

  // A cell cited from the HR system's terms: whose, which term, its value and the column's unit.
  private record Term(String employee, String column, double value, String unit) {}

  // A source the answer cites, with the display it is shown under.
  private record Cited(Object display, Map<String, Object> source) {}

  @Override
  public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
    List<String> strangers = strangers(answer, evidence, aisolate);
    if (!strangers.isEmpty()
        && !answer.text().strip().toLowerCase(Locale.ROOT).startsWith(DONT_KNOW)) {
      return Verdict.fail(
          "nothing of "
              + String.join(" and ", strangers)
              + " was read, so the answer had to be I don't know");
    }
    if (answer.groundingJson().isEmpty()) {
      return Verdict.pass("no figure to check");
    }
    Map<String, Object> grounding = object(Json.parse(answer.groundingJson()));
    String mismatched =
        array(grounding.get("figures")).stream()
            .map(ContractTermsVerifier::object)
            .filter(figure -> "mismatch".equals(figure.get("status")))
            .map(figure ->
                figure.get("display")
                    + " is not what the answer claims: "
                    + (text(figure.get("reason")) == null
                        ? "its row says otherwise"
                        : figure.get("reason")))
            .collect(Collectors.joining("; "));
    if (!mismatched.isEmpty()) {
      return Verdict.fail(mismatched);
    }
    // A tool result is a JSON object, the evidence of passages a list.
    Map<String, Map<String, Object>> results = new HashMap<>();
    for (Evidence each : evidence) {
      if (each.contentJson().startsWith("{")) {
        results.put(each.id(), object(Json.parse(each.contentJson())));
      }
    }
    // A contract that could not be read is kept as null, so it is asked for once.
    Map<String, List<Section>> contracts = new HashMap<>();
    List<String> checked = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    for (Cited cited : sources(grounding)) {
      Term term = term(cited.source(), results);
      if (term == null) {
        continue;
      }
      String employee = term.employee();
      if (!contracts.containsKey(employee)) {
        contracts.put(employee, readContract(employee, aisolate));
      }
      List<Section> sections = contracts.get(employee);
      if (sections == null) {
        problems.add(
            employee + "'s contract could not be read: " + cited.display() + " is unchecked");
        continue;
      }
      Stated stated = stated(term.column(), sections);
      String where = cited.display() + " is not in " + employee + "'s contract: ";
      if (stated == null) {
        problems.add(where + "no section states it");
      } else if (stated.number() != term.value()) {
        problems.add(where + stated.section() + " says " + show(stated.number(), term.unit()));
      } else {
        checked.add(cited.display() + " is in " + employee + "'s contract: " + stated.section());
      }
    }
    if (!problems.isEmpty()) {
      return Verdict.warn(String.join("; ", problems));
    }
    if (checked.isEmpty()) {
      return Verdict.pass("no figure to check");
    }
    return Verdict.pass(
        checked.size() == 1 ? checked.getFirst() : "every figure is in the contract");
  }

  // The employees the question or the answer names, by full or first name, of whom no evidence was
  // read: a row carries the employee, a passage the contract's title. Nobody when the data source
  // hr is unavailable.
  private static List<String> strangers(
      Answer answer, List<Evidence> evidence, AIsolateManager aisolate) {
    String mentioned = answer.question() + " " + answer.text();
    String read = evidence.stream().map(Evidence::contentJson).collect(Collectors.joining(" "));
    // ponytail: a first name alone; two employees sharing one, or one named like a word ("May"),
    // need the full name.
    return employees(aisolate).stream()
        .filter(name -> names(name, mentioned) && !names(name, read))
        .toList();
  }

  private static boolean names(String employee, String text) {
    String first = employee.strip().split("\\s+")[0];
    // UNICODE_CHARACTER_CLASS: Java's \b is ASCII, so an accented letter would end a word.
    return Pattern.compile(
            "\\b(" + Pattern.quote(employee) + "|" + Pattern.quote(first) + ")\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS)
        .matcher(text)
        .find();
  }

  private static List<String> employees(AIsolateManager aisolate) {
    List<String> employees = new ArrayList<>();
    try (Connection db = aisolate.dataSource("hr").getConnection();
        PreparedStatement query = db.prepareStatement(EMPLOYEES);
        ResultSet rows = query.executeQuery()) {
      while (rows.next()) {
        String employee = rows.getString(1);
        // A blank name would be a word between any two words.
        if (employee != null && !employee.isBlank()) {
          employees.add(employee);
        }
      }
      return employees;
    } catch (IllegalArgumentException | SQLException unavailable) {
      aisolate.log().log(
          System.Logger.Level.WARNING,
          "employees unavailable: " + ContractsContextPreparator.unavailableCause(unavailable));
      return List.of();
    }
  }

  // The cell cited from the HR system's terms; null for anything else: a passage, another entity,
  // an aggregate, a column not compared, or a row or value the result does not hold.
  private static Term term(Map<String, Object> source, Map<String, Map<String, Object>> results) {
    Map<String, Object> result = results.get(source.get("evidence"));
    if (result == null || !(source.get("column") instanceof String column)) {
      return null;
    }
    // The salary comes from fetch_salary's entity (the same table, the salary alone) or from the
    // terms.
    Object entity = result.get("entity");
    if (!(ContractTools.TERMS.name().equals(entity) || ContractTools.SALARY.name().equals(entity))
        || !STATED.containsKey(column)) {
      return null;
    }
    // labels are null for a result a tool built itself
    Map<String, Object> labels = object(result.get("labels"));
    String rowKey = text(labels.get("row_key"));
    if (text(labels.get("function")) != null || rowKey == null) {
      return null;
    }
    Map<String, Object> row =
        array(result.get("rows")).stream()
            .map(ContractTermsVerifier::object)
            .filter(each ->
                each.containsKey(rowKey)
                    && String.valueOf(each.get(rowKey)).equals(source.get("row_key")))
            .findFirst()
            .orElse(null);
    if (row == null) {
      return null;
    }
    Double value = number(row.get(column));
    if (!(row.get(ContractTools.TERMS.subjectName()) instanceof String employee) || value == null) {
      return null;
    }
    return new Term(employee, column, value, text(object(labels.get("units")).get(column)));
  }

  // The section that states the term and its number, the last statement winning; null when none
  // does.
  private static Stated stated(String column, List<Section> sections) {
    // ponytail: the sections' order decides; parse "with effect from <date>" when amendments arrive
    // out of order.
    Stated stated = null;
    for (Section section : sections) {
      Matcher match = STATED.get(column).matcher(section.body());
      while (match.find()) {
        stated = new Stated(section.name(), Double.parseDouble(match.group(1).replace(",", "")));
      }
    }
    return stated;
  }

  // The employee's contract as filed in the data source hr; null when the airlock has no data
  // source hr or the database does not answer, which the verdict says.
  private static List<Section> readContract(String employee, AIsolateManager aisolate) {
    List<Section> sections = new ArrayList<>();
    try (Connection db = aisolate.dataSource("hr").getConnection();
        PreparedStatement query = db.prepareStatement(SECTIONS)) {
      query.setString(1, ContractsContextPreparator.CONTEXT_PREPARATOR);
      query.setString(2, TITLE + employee);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          sections.add(new Section(rows.getString(1), rows.getString(2)));
        }
      }
      return sections;
    } catch (IllegalArgumentException | SQLException unavailable) {
      aisolate.log().log(
          System.Logger.Level.WARNING,
          "contract of "
              + employee
              + " unavailable: "
              + ContractsContextPreparator.unavailableCause(unavailable));
      return null;
    }
  }

  // A cell's value as a number: a JSON number, or digits in a string; null for anything else.
  private static Double number(Object raw) {
    if (raw instanceof Number number) {
      return number.doubleValue();
    }
    if (raw instanceof String text) {
      try {
        return new BigDecimal(text.replace(",", "").strip()).doubleValue();
      } catch (NumberFormatException notANumber) {
        return null;
      }
    }
    return null;
  }

  // A contract's number as the answer shows a figure: thousands grouped, the unit after it. Whole:
  // the patterns capture digits alone.
  private static String show(double number, String unit) {
    String text = String.format(Locale.ROOT, "%,.0f", number);
    return unit == null ? text : text + " " + unit;
  }

  // Each source the answer cites, with the display it is shown under: a figure's, its computation's
  // operands, ranked rows and row, a table cell's. Any status counts: grounding keeps the source of
  // a flagged figure, and its flag says nothing of the contract. A null is left out of the
  // grounding, so a source may be missing.
  private static List<Cited> sources(Map<String, Object> grounding) {
    List<Cited> sources = new ArrayList<>();
    for (Object item : array(grounding.get("figures"))) {
      Map<String, Object> figure = object(item);
      Map<String, Object> computed = object(figure.get("computed"));
      sources.add(new Cited(figure.get("display"), object(figure.get("source"))));
      for (Object operand : array(computed.get("of"))) {
        sources.add(new Cited(object(operand).get("display"), object(operand)));
      }
      for (Object ranked : array(computed.get("rows"))) {
        sources.add(new Cited(object(ranked).get("display"), object(ranked)));
      }
      sources.add(new Cited(figure.get("display"), object(computed.get("row"))));
    }
    for (Object table : array(grounding.get("tables"))) {
      for (Object row : array(object(table).get("rows"))) {
        for (Object item : array(row)) {
          Map<String, Object> cell = object(item);
          sources.add(new Cited(cell.get("display"), object(cell.get("source"))));
        }
      }
    }
    return sources;
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

  private static String text(Object json) {
    return json instanceof String string && !string.isEmpty() ? string : null;
  }
}
