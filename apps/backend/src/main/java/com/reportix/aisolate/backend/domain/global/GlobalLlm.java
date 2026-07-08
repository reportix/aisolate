package com.reportix.aisolate.backend.domain.global;

import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import java.time.OffsetDateTime;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A model the admin defined for the whole install: every space inherits it. An API key sits in the
 * Secret {@code secretRef} names.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GlobalLlm(
    String id,
    String provider,
    String url,
    String model,
    String secretRef,
    String createdBy,
    String createdByName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  /** The row as a form holds it, to check and keep its Secret as a space's rows are. */
  public LlmFormDTO toRow() {
    return new LlmFormDTO(id, provider, url, model, secretRef, null);
  }
}
