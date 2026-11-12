package com.reportix.aisolate.backend.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The airlock the API returns: its form's fields at the top level too, beside {@code form} itself,
 * which the UI reads and sends back as a save, and how many versions it has. The wire shape is the
 * contract, whichever way the record holds it.
 */
class AirlockJsonTest {

  // The application configures nothing on its mapper: the naming strategy is on the classes.
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final AirlockFormDTO FORM =
      new AirlockFormDTO(
          "hr-assistant",
          "HR assistant",
          "From the UI.",
          List.of("employee"),
          List.of("*"),
          List.of("acme-batch"),
          List.of(
              new LlmFormDTO(
                  "granite", "ollama", "http://ollama:11434", "granite4.1:8b", null, null)),
          List.of(
              new DataSourceFormDTO(
                  "acme_hr", "jdbc:postgresql://db:5432/hr", "hr-ds-acme-hr-a1b2c3", null, null)));

  private static final Airlock AIRLOCK =
      new Airlock(
          "hr-assistant",
          "hr-acme",
          3,
          2,
          "seed",
          "Seed User",
          OffsetDateTime.parse("2026-10-01T08:00:00Z"),
          OffsetDateTime.parse("2026-10-02T09:30:00Z"),
          FORM);

  private static Set<String> keys(JsonNode node) {
    return Set.copyOf(node.propertyNames());
  }

  @Test
  void givenAnAirlock_whenSerialised_thenTheEightFormFieldsStayAtTheTopLevelBesideForm() {
    // Arrange
    // Act
    JsonNode json = JSON.readTree(JSON.writeValueAsString(AIRLOCK));

    // Assert
    assertThat(json.propertyNames())
        .containsExactly(
            "id",
            "space_id",
            "name",
            "description",
            "roles",
            "callers",
            "clients",
            "llms",
            "data_sources",
            "revision",
            "versions",
            "created_by",
            "created_by_name",
            "created_at",
            "updated_at",
            "form");
    JsonNode form = json.get("form");
    for (String key :
        List.of(
            "id", "name", "description", "roles", "callers", "clients", "llms", "data_sources")) {
      assertThat(json.get(key)).as(key).isEqualTo(form.get(key));
    }
    assertThat(json.get("name").asString()).isEqualTo("HR assistant");
    assertThat(json.get("roles")).hasSize(1);
    assertThat(json.get("llms").get(0).get("id").asString()).isEqualTo("granite");
    assertThat(json.get("data_sources").get(0).get("id").asString()).isEqualTo("acme_hr");
    assertThat(json.get("versions").asInt()).isEqualTo(2);
  }

  @Test
  void givenAnAirlock_whenSerialised_thenFormCarriesAllItsFieldsForTheUiToSendBack() {
    // Arrange
    // Act
    JsonNode form = JSON.readTree(JSON.writeValueAsString(AIRLOCK)).get("form");

    // Assert: nothing of what runs, which is a version's
    assertThat(keys(form))
        .containsExactlyInAnyOrder(
            "id", "name", "description", "roles", "callers", "clients", "llms", "data_sources");
    assertThat(JSON.treeToValue(form, AirlockFormDTO.class)).isEqualTo(FORM);
  }
}
