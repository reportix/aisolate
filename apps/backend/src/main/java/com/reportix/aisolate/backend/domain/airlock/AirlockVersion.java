package com.reportix.aisolate.backend.domain.airlock;

import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.config.Llm;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One airlock version as the AIsolate UI shows it: the endpoint it serves on, the editable form,
 * the LLM that answers and, read-only, the code {@code aisolate deploy} brought, null before the
 * first deploy. {@code revision} counts the saves of this version.
 *
 * @param mainLlm the LLM its {@code llm} names, null when that resolves to nothing
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AirlockVersion(
    String airlockId,
    String version,
    String description,
    int revision,
    String endpoint,
    String createdBy,
    String createdByName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    VersionFormDTO form,
    MainLlm mainLlm,
    JarInspector.Contents jar) {

  /**
   * @param from where it is defined: {@code version}, {@code airlock}, {@code space} or {@code
   *     global}
   */
  public record MainLlm(String id, String provider, String url, String model, String from) {

    /** The nearest level's LLM of that id, as the controller resolves it; null for none. */
    public static MainLlm of(String id, Map<String, List<Llm>> nearestFirst) {
      return nearestFirst.entrySet().stream()
          .flatMap(level ->
              level.getValue().stream()
                  .filter(llm -> llm.id().equals(id))
                  .map(llm ->
                      new MainLlm(id, llm.provider(), llm.url(), llm.model(), level.getKey())))
          .findFirst()
          .orElse(null);
    }
  }
}
