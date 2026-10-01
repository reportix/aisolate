package com.reportix.aisolate.backend.domain.airlock;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.config.Llm;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One airlock as the AIsolate UI shows it: who may ask it besides its space's users (its own users,
 * the clients they may call from, and the machine clients it admits without an e-mail), the LLMs
 * and data sources it sees beside the space's and the global ones, the endpoint it serves on, the
 * form the UI edits, the LLM that answers and, read-only, the code {@code aisolate deploy} brought,
 * null before the first deploy. {@code revision} counts its saves.
 *
 * <p>The {@code form} holds the name, description, users, callers, clients, LLMs and data sources
 * once; the record answers them from there ({@link #name()} and the like; the id stays a record
 * component beside {@code form.id()}) and the JSON repeats them at the top level beside {@code
 * form}, as the AIsolate UI reads them, in the order the UI has always had.
 *
 * @param mainLlm the LLM its {@code llm} names, null when that resolves to nothing
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder({
  "id",
  "space_id",
  "name",
  "description",
  "users",
  "callers",
  "clients",
  "llms",
  "data_sources",
  "revision",
  "endpoint",
  "created_by",
  "created_by_name",
  "created_at",
  "updated_at",
  "form",
  "main_llm",
  "jar"
})
public record Airlock(
    String id,
    String spaceId,
    int revision,
    String endpoint,
    String createdBy,
    String createdByName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    AirlockFormDTO form,
    MainLlm mainLlm,
    JarInspector.Contents jar) {

  /**
   * @param from where it is defined: {@code airlock}, {@code space} or {@code global}
   */
  public record MainLlm(String id, String provider, String url, String model, String from) {

    /** The nearest level's LLM of that id, as the controller resolves it; null for none. */
    public static MainLlm of(String id, Map<String, List<Llm>> nearestFirst) {
      return nearestFirst.entrySet().stream()
          .flatMap(
              level ->
                  level.getValue().stream()
                      .filter(llm -> llm.id().equals(id))
                      .map(
                          llm ->
                              new MainLlm(
                                  id, llm.provider(), llm.url(), llm.model(), level.getKey())))
          .findFirst()
          .orElse(null);
    }
  }

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
  public List<String> users() {
    return form.users();
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

  public Airlock withMainLlm(MainLlm llm) {
    return new Airlock(
        id,
        spaceId,
        revision,
        endpoint,
        createdBy,
        createdByName,
        createdAt,
        updatedAt,
        form,
        llm,
        jar);
  }
}
