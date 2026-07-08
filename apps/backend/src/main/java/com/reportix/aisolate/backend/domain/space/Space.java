package com.reportix.aisolate.backend.domain.space;

import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One team's or product's space as the AIsolate UI shows it: its form's fields, its members with
 * their role, and the LLMs and data sources its airlocks share beside the global ones.
 *
 * @param callerRole what the caller may do here: {@code admin} (everything) or {@code developer}
 *     (its airlocks); null where no caller is known
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Space(
    String id,
    String name,
    String description,
    List<SpaceFormDTO.Member> members,
    List<LlmFormDTO> llms,
    List<DataSourceFormDTO> dataSources,
    String createdBy,
    String createdByName,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String callerRole) {

  public static final String ADMIN = "admin";

  /** Every member's e-mail: who may ask its airlocks, as the Space resource lists them. */
  public List<String> users() {
    return members.stream().map(SpaceFormDTO.Member::email).toList();
  }

  /** The member's role, e-mails compared without case as admission compares them. */
  public Optional<String> roleOf(String email) {
    return email == null
        ? Optional.empty()
        : members.stream()
            .filter(m -> m.email().equalsIgnoreCase(email))
            .map(SpaceFormDTO.Member::role)
            .findFirst();
  }

  public Space withCallerRole(String role) {
    return new Space(
        id,
        name,
        description,
        members,
        llms,
        dataSources,
        createdBy,
        createdByName,
        createdAt,
        updatedAt,
        role);
  }
}
