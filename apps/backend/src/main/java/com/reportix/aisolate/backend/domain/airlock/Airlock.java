package com.reportix.aisolate.backend.domain.airlock;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One airlock as the AIsolate UI shows it: who may ask it besides its space's roles (its own roles,
 * the clients they may call from, and the machine clients it admits without an e-mail), the LLMs
 * and data sources its versions see beside the space's and the global ones, how many versions it
 * has, and the form the UI edits. The airlock runs nothing itself; each of its versions does, on
 * its own endpoint. {@code revision} counts its saves.
 *
 * <p>The {@code form} holds the name, description, roles, callers, clients, LLMs and data sources
 * once; the record answers them from there ({@link #name()} and the like; the id stays a record
 * component beside {@code form.id()}) and the JSON repeats them at the top level beside {@code
 * form}, as the AIsolate UI reads them, in the order the UI has always had.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder({
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
  "form"
})
public record Airlock(
    String id,
    String spaceId,
    int revision,
    int versions,
    String createdBy,
    String createdByName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    AirlockFormDTO form) {

  /** How messages and logs name it: {@code hr-space/hr-contracts}. */
  public String label() {
    return spaceId + "/" + id;
  }

  @JsonProperty
  public String name() {
    return form.name();
  }

  @JsonProperty
  public String description() {
    return form.description();
  }

  @JsonProperty
  public List<String> roles() {
    return form.roles();
  }

  @JsonProperty
  public List<String> callers() {
    return form.callers();
  }

  @JsonProperty
  public List<String> clients() {
    return form.clients();
  }

  @JsonProperty
  public List<LlmFormDTO> llms() {
    return form.llms();
  }

  @JsonProperty
  public List<DataSourceFormDTO> dataSources() {
    return form.dataSources();
  }
}
