package com.reportix.aisolate.backend.domain.space;

import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One team's or product's space as the AIsolate UI shows it: its form's fields, its developers (by
 * e-mail), the roles that may ask every airlock of it, and the LLMs and data sources its airlocks
 * share beside the global ones.
 *
 * @param callerRole what the caller may do here: {@code admin} (everything) or {@code developer}
 *     (its airlocks); null where no caller is known
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Space(
    String id,
    String name,
    String description,
    List<String> developers,
    List<String> roles,
    List<LlmFormDTO> llms,
    List<DataSourceFormDTO> dataSources,
    String createdBy,
    String createdByName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String callerRole) {

  public static final String ADMIN = "admin";
  public static final String DEVELOPER = "developer";

  /** Whether the e-mail is one of its developers', compared without case. */
  public boolean develops(String email) {
    return email != null && developers.stream().anyMatch(email::equalsIgnoreCase);
  }

  public Space withCallerRole(String role) {
    return new Space(
        id,
        name,
        description,
        developers,
        roles,
        llms,
        dataSources,
        createdBy,
        createdByName,
        createdAt,
        updatedAt,
        role);
  }
}
