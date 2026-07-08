package com.reportix.aisolate.backend.domain.global;

import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import java.time.OffsetDateTime;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A data source the admin defined for the whole install: every space's code may read it. Its
 * credentials sit in the Secret {@code secretRef} names.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GlobalDataSource(
    String id,
    String url,
    String secretRef,
    String createdBy,
    String createdByName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  /** The row as a form holds it, to check and keep its Secret as a space's rows are. */
  public DataSourceFormDTO toRow() {
    return new DataSourceFormDTO(id, url, secretRef, null, null);
  }
}
