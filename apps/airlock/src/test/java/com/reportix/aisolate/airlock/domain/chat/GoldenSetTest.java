package com.reportix.aisolate.airlock.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.sdk.Passage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The golden set: per template, answers in the format over the evidence its tools return, each with
 * one injected error (or none), and the status every figure must get. No model is asked: the same
 * answer over the same evidence must always come out the same. Fixtures in {@code
 * conformance/cases/grounding}, one per template, shared with the Python airlock; the labels of
 * their evidence are the templates' own entities', which {@code TemplateBuildTest} checks.
 */
class GoldenSetTest {

  static final List<String> TEMPLATES =
      List.of("company-hr", "company-sales", "auditor", "hospital");

  /** Every template's set covers each of these. */
  static final Set<String> ERRORS =
      Set.of(
          "correct",
          "wrong_subject",
          "wrong_period",
          "wrong_concept",
          "wrong_unit",
          "wrong_scope",
          "typed_computation",
          "fabricated",
          "scale",
          "sign",
          "table_wrong_order",
          "table_missing_row",
          "table_other_evidence",
          "ranking_wrong_order",
          "ranking_missing_row",
          "ranking_other_evidence");

  /** As the airlock reads a tool result: exact decimals. */
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  static Stream<Arguments> cases() {
    List<Arguments> cases = new ArrayList<>();
    for (String template : TEMPLATES) {
      JsonNode fixture = fixture(template);
      for (JsonNode golden : fixture.path("cases")) {
        String name =
            template
                + " · "
                + golden.path("error").asString()
                + " · "
                + golden.path("name").asString();
        cases.add(arguments(named(name, golden), fixture));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenModelAnswerOverTheTemplatesEvidence_whenGrounded_thenEveryFigureHasItsExpectedStatus(
      JsonNode golden, JsonNode fixture) {
    // Arrange
    Grounding grounding = grounding(fixture, golden.path("question").asString());
    AnswerFormat.ModelAnswer answer =
        AnswerFormat.parse(JSON.writeValueAsString(golden.path("answer")));

    // Act
    GroundedAnswer grounded = grounding.ground(answer);

    // Assert
    Map<String, String> statuses = new LinkedHashMap<>();
    grounded.figures().forEach(figure -> statuses.put(figure.id(), figure.status()));
    grounded.tables().forEach(table -> statuses.put(table.id(), table.status()));
    assertThat(statuses).isEqualTo(strings(golden.path("expect")));
    if (golden.has("quotes")) {
      Map<String, String> quotes = new LinkedHashMap<>();
      grounded.quotes().forEach(quote -> quotes.put(quote.id(), quote.status()));
      assertThat(quotes).isEqualTo(strings(golden.path("quotes")));
    }
    if (golden.has("rendered")) {
      assertThat(grounded.answer()).isEqualTo(golden.path("rendered").asString());
    }
    if (golden.has("reason")) {
      List<String> reasons = new ArrayList<>();
      grounded.figures().forEach(figure -> reasons.add(String.valueOf(figure.reason())));
      grounded.tables().forEach(table -> reasons.add(String.valueOf(table.reason())));
      assertThat(reasons).anyMatch(reason -> reason.contains(golden.path("reason").asString()));
    }
    // A masked value never stands for a figure, in the text or beside it
    assertThat(grounded.answer()).doesNotContain("****");
    assertThat(grounded.figures()).noneMatch(figure -> figure.display().contains("****"));
    // Deterministic: the same answer over the same evidence, the same result
    assertThat(grounding(fixture, golden.path("question").asString()).ground(answer))
        .isEqualTo(grounded);
  }

  @Test
  void givenGoldenSets_whenRead_thenEachTemplateCoversEveryErrorAndEachErrorIsCaught() {
    // Arrange
    Map<String, Set<String>> covered = new LinkedHashMap<>();

    // Act
    for (String template : TEMPLATES) {
      JsonNode fixture = fixture(template);
      covered.put(template, new TreeSet<>());
      for (JsonNode golden : fixture.path("cases")) {
        String error = golden.path("error").asString();
        covered.get(template).add(error);
        // An injected error expects a mismatch or an unsourced figure: never a clean answer
        if (!error.equals("correct")) {
          assertThat(strings(golden.path("expect")).values())
              .as("%s %s", template, golden.path("name").asString())
              .containsAnyOf(Grounding.MISMATCH, Grounding.UNSOURCED);
        }
      }
    }

    // Assert
    covered.forEach((template, errors) -> assertThat(errors).as(template).containsAll(ERRORS));
  }

  @Test
  void givenGoldenSets_whenRead_thenRowHandlesAreNumberedAcrossTheExchangeAsTheAirlockDoes() {
    for (String template : TEMPLATES) {
      // Arrange
      List<String> handles = new ArrayList<>();

      // Act
      for (JsonNode evidence : fixture(template).path("evidence")) {
        evidence.path("rows").propertyNames().forEach(handles::add);
      }

      // Assert
      assertThat(handles)
          .as(template)
          .isEqualTo(
              Stream.iterate(1, i -> i + 1).limit(handles.size()).map(i -> "r" + i).toList());
    }
  }

  /** The exchange's evidence as the airlock keeps it for grounding. */
  static Grounding grounding(JsonNode fixture, String question) {
    List<Grounding.Rows> rows = new ArrayList<>();
    List<Grounding.Passages> documents = new ArrayList<>();
    for (JsonNode evidence : fixture.path("evidence")) {
      String id = evidence.path("id").asString();
      if (evidence.has("passages")) {
        documents.add(
            new Grounding.Passages(
                id,
                JSON.treeToValue(
                    evidence.path("passages"), new TypeReference<List<Passage>>() {})));
        continue;
      }
      Map<String, Map<String, Object>> byHandle =
          JSON.treeToValue(
              evidence.path("rows"),
              new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {});
      rows.add(
          new Grounding.Rows(
              id,
              new GroundedAnswer.Call(
                  evidence.path("tool").asString(),
                  JSON.treeToValue(
                      evidence.path("args"), new TypeReference<Map<String, Object>>() {})),
              evidence.path("entity").asString(),
              JSON.treeToValue(evidence.path("columns"), new TypeReference<List<String>>() {}),
              JSON.treeToValue(evidence.path("labels"), ToolEvidence.Labels.class),
              byHandle));
    }
    return new Grounding(rows, documents, question);
  }

  static JsonNode fixture(String template) {
    String cases = System.getProperty("aisolate.conformance.dir");
    if (cases == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    return JSON.readTree(Path.of(cases, "grounding", template + ".json").toFile());
  }

  private static Map<String, String> strings(JsonNode object) {
    Map<String, String> strings = new LinkedHashMap<>();
    object.propertyNames().forEach(key -> strings.put(key, object.path(key).asString()));
    return strings;
  }
}
