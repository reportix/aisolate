package com.aldermoor.ehr.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.sdk.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EhrToolsTest {

  /** Records what a tool asks of the governed query; returns no rows. */
  static final class RecordingQuery implements GovernedQuery {
    final List<String> calls = new ArrayList<>();

    @Override
    public Result read(String entity, List<Filter> filters, int limit) {
      calls.add("read " + entity + " " + filters + " " + limit);
      return null;
    }

    @Override
    public Result aggregate(
        String entity, Fn function, String column, String groupBy, List<Filter> filters) {
      calls.add("aggregate " + entity + " " + function + " " + column + " by " + groupBy);
      return null;
    }
  }

  private final RecordingQuery query = new RecordingQuery();

  @Test
  void givenPlugin_whenTools_thenAPairPerDepartmentAndTheSharedMedicationList() {
    // Arrange: aisolate.yaml names read_<airlock>_records and aggregate_<airlock>_records
    EhrTools plugin = new EhrTools();

    // Act
    List<String> names = plugin.tools().stream().map(Tool::name).toList();

    // Assert
    assertThat(names)
        .containsExactly(
            "read_cardiology_records",
            "aggregate_cardiology_records",
            "read_psychiatry_records",
            "aggregate_psychiatry_records",
            "read_medications");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "read_cardiology_records | psychiatry.sessions | [cardiology.vitals, cardiology.ecgs,"
            + " cardiology.labs]",
        "aggregate_cardiology_records | psychiatry.diagnoses | [cardiology.vitals,"
            + " cardiology.ecgs, cardiology.labs]",
        "read_psychiatry_records | cardiology.labs | [psychiatry.sessions, psychiatry.diagnoses]",
        "aggregate_psychiatry_records | ehr.medications | [psychiatry.sessions,"
            + " psychiatry.diagnoses]"
      })
  void givenAnotherDepartmentsEntity_whenCalled_thenRefusedWithoutAQuery(
      String tool, String entity, String choices) {
    // Arrange
    Map<String, Object> args = Map.of("entity", entity, "function", "count", "column", "patient");

    // Act
    Throwable thrown = catchThrowable(() -> tool(tool).handler().call(query, args));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown entity " + entity + "; one of " + choices);
    assertThat(query.calls).isEmpty();
  }

  @Test
  void givenGroupBy_whenAggregateCardiologyRecords_thenGroupedByIt() {
    // Arrange: the chief's question, average blood pressure by age group
    Map<String, Object> args =
        Map.of(
            "entity",
            "cardiology.vitals",
            "function",
            "avg",
            "column",
            "systolic_bp",
            "group_by",
            "age_group");

    // Act
    tool("aggregate_cardiology_records").handler().call(query, args);

    // Assert
    assertThat(query.calls)
        .containsExactly("aggregate cardiology.vitals AVG systolic_bp by age_group");
  }

  @ParameterizedTest
  @CsvSource({
    "read_medications, ehr.medications, 1",
    "read_medications, ehr.medications, 500",
    "read_cardiology_records, cardiology.labs, 1"
  })
  void givenAnyLimit_whenRead_thenEveryRowUpTo20(String tool, String entity, int limit) {
    // Arrange: a limit of 1 asked for "the last" value read the oldest row, so none is offered
    Map<String, Object> args =
        Map.of(
            "entity",
            entity,
            "filters",
            List.of(Map.of("column", "patient", "op", "ilike", "value", "Julian")),
            "limit",
            limit);

    // Act
    tool(tool).handler().call(query, args);

    // Assert
    assertThat(query.calls)
        .containsExactly("read " + entity + " [Filter[column=patient, op=ILIKE, value=Julian]] 20");
    assertThat(tool(tool).inputSchema().toString()).doesNotContain("limit");
  }

  @Test
  void givenEveryEntity_whenColumns_thenEachHasThePatientsNameIdNationalIdAndAgeBand() {
    // Arrange: a rule masking or hiding a column the entity lacks stops the tool server
    List<Entity> entities = new EhrTools().entities();

    // Act
    List<String> lacking =
        entities.stream()
            .filter(
                e ->
                    !e.columns()
                        .keySet()
                        .containsAll(List.of("patient", "patient_id", "national_id", "age_group")))
            .map(Entity::name)
            .toList();

    // Assert
    assertThat(entities).hasSize(6);
    assertThat(lacking).isEmpty();
  }

  @Test
  void givenEveryEntity_whenSubject_thenThePatientNotTheClinician() {
    // Arrange: a minimum group counts distinct subjects, and self marks only the identity's own
    EhrTools plugin = new EhrTools();

    // Act
    List<String> subjects =
        plugin.entities().stream().map(e -> e.column(e.subject()).expression()).toList();

    // Assert
    assertThat(subjects).hasSize(6).containsOnly("p.id");
    assertThat(plugin.entities()).noneMatch(e -> e.subject().equals(plugin.identityAttribute()));
  }

  @Test
  void givenPlugin_whenIdentityJoin_thenTheAskingClinicianByEmail() {
    // Arrange
    EhrTools plugin = new EhrTools();

    // Act
    String join = plugin.identityJoin();

    // Assert
    assertThat(join).isEqualTo("SELECT id FROM ehr.clinicians WHERE email = ?");
    assertThat(plugin.identityAttribute()).isEqualTo("clinician_id");
  }

  @ParameterizedTest
  @CsvSource({
    "cardiology.vitals, cardiology",
    "cardiology.ecgs, cardiology",
    "cardiology.labs, cardiology",
    "psychiatry.sessions, psychiatry",
    "psychiatry.diagnoses, psychiatry",
    "ehr.medications, cardiology psychiatry"
  })
  void givenEntity_whenFrom_thenItJoinsExactlyItsDepartmentsCareTeams(
      String name, String departments) {
    // Arrange: one clinician per patient and department, so a LEFT JOIN never repeats a row
    Entity entity = new EhrTools().entity(name).orElseThrow();
    List<String> expected = List.of(departments.split(" "));

    // Act
    List<String> joined = new ArrayList<>();
    Matcher team =
        Pattern.compile(
                "LEFT JOIN ehr\\.care_team (\\w+)_team ON \\1_team\\.patient_id = p\\.id AND"
                    + " \\1_team\\.department = '\\1'")
            .matcher(entity.from());
    while (team.find()) {
      joined.add(team.group(1));
    }

    // Assert
    assertThat(joined).containsExactlyElementsOf(expected);
    assertThat(entity.columns().keySet().stream().filter(c -> c.endsWith("_clinician")))
        .containsExactlyElementsOf(expected.stream().map(d -> d + "_clinician").toList());
    expected.forEach(
        d ->
            assertThat(entity.column(d + "_clinician").expression())
                .isEqualTo(d + "_team.clinician_id"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "cardiology.vitals",
        "cardiology.ecgs",
        "cardiology.labs",
        "psychiatry.sessions",
        "psychiatry.diagnoses",
        "ehr.medications"
      })
  void givenRuleSet_whenRead_thenItNamesOnlyTheEntitysColumnsAndFiltersByTheClinician(String name)
      throws IOException {
    // Arrange: a created project runs its tests from its own directory, the CLI's build from
    // apps/cli
    Path folder =
        Files.isDirectory(Path.of("rules"))
            ? Path.of("rules")
            : Path.of("src/main/templates/hospital/rules");
    String json = Files.readString(folder.resolve(name + ".json"));
    EhrTools plugin = new EhrTools();
    Entity entity = plugin.entity(name).orElseThrow();

    // Act
    List<String> filtered = new ArrayList<>();
    List<String> attributes = new ArrayList<>();
    Matcher filter =
        Pattern.compile("\"row_filter\": \"(\\w+) == principal\\.(\\w+)\"").matcher(json);
    while (filter.find()) {
      filtered.add(filter.group(1));
      attributes.add(filter.group(2));
    }
    List<String> named = new ArrayList<>();
    Matcher column = Pattern.compile("\"(\\w+)\": \"(?:hide|mask)\"").matcher(json);
    while (column.find()) {
      named.add(column.group(1));
    }
    String[] parts = json.split("\\{\"name\": ");
    List<String> rules = List.of(parts).subList(1, parts.length);

    // Assert: every doctor rule filters by a care team the entity joins; every rule hides them all;
    // the chief's also hides the patient's id
    assertThat(filtered).isNotEmpty().allMatch(c -> c.endsWith("_clinician"));
    assertThat(attributes).containsOnly(plugin.identityAttribute());
    assertThat(entity.columns().keySet()).containsAll(filtered).containsAll(named);
    assertThat(rules)
        .hasSize(filtered.size() + 1)
        .allSatisfy(
            rule -> filtered.forEach(c -> assertThat(rule).contains("\"" + c + "\": \"hide\"")));
    assertThat(rules.getLast())
        .startsWith("\"chief-statistics\"")
        .contains("\"patient_id\": \"hide\"");
  }

  @ParameterizedTest
  @CsvSource({"ehr.medications,'dose,drug,frequency'", "psychiatry.diagnoses,'diagnosis,icd10'"})
  void givenEntity_whenColumns_thenItsFactColumnsAreTheClinicalValuesAModelMayType(
      String name, String facts) {
    // Arrange: a typed value of a fact column is shown as unsourced, so only what a reader must not
    // take from the model's memory is marked
    Entity entity = new EhrTools().entity(name).orElseThrow();

    // Act
    List<String> marked =
        entity.columns().values().stream().filter(Column::fact).map(Column::name).sorted().toList();

    // Assert
    assertThat(marked).containsExactlyElementsOf(List.of(facts.split(",")));
  }

  private static Tool tool(String name) {
    return new EhrTools()
        .tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }
}
