package com.reportix.aisolate.backend.domain.space;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import com.reportix.aisolate.config.Names;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What the admin fills in for a space: its identity, its members (by the token's e-mail) with their
 * role, and the LLMs and data sources its airlocks share beside the global ones, each optional.
 * Every member may ask its airlocks, as may an airlock's own users; a developer also edits its
 * airlocks and deploys into it. Validated here; stored as the columns and the definition JSON of a
 * space.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpaceFormDTO(
    String id,
    String name,
    String description,
    List<Member> members,
    List<LlmFormDTO> llms,
    List<DataSourceFormDTO> dataSources) {

  public static final String DEVELOPER = "developer";
  public static final String USER = "user";

  /**
   * @param role {@link #DEVELOPER} or {@link #USER}; a developer may ask as well
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Member(String email, String role) {
    public Member {
      email = strip(email);
      role = strip(role);
    }
  }

  public SpaceFormDTO {
    // The label is optional: without one the id is the name every reader shows.
    name = name == null || name.isBlank() ? id : name;
    description = strip(description);
    members = members == null ? List.of() : members.stream().filter(Objects::nonNull).toList();
    llms = LlmFormDTO.clean(llms);
    dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
  }

  /** Without an id, a free one from the name ({@link AirlockFormDTO#deriveId}). */
  public SpaceFormDTO withIdFromName(Predicate<String> taken) {
    return AirlockFormDTO.hasIdOrNoName(id, name)
        ? this
        : new SpaceFormDTO(
            AirlockFormDTO.deriveId(name, "space", AirlockFormDTO.MAX_ID, taken),
            name,
            description,
            members,
            llms,
            dataSources);
  }

  public SpaceFormDTO withDataSources(List<DataSourceFormDTO> rows) {
    return new SpaceFormDTO(id, name, description, members, llms, rows);
  }

  public SpaceFormDTO withLlms(List<LlmFormDTO> rows) {
    return new SpaceFormDTO(id, name, description, members, rows, dataSources);
  }

  /** One message per problem, each naming the field it is about. Empty means valid. */
  public List<String> validate() {
    List<String> errors = new ArrayList<>();
    if (id == null || !Names.SLUG.matcher(id).matches()) {
      errors.add("id: " + Names.SLUG_RULE);
    }
    Names.userProblems(emails()).forEach(problem -> errors.add("members: " + problem));
    members.stream()
        .filter(m -> !DEVELOPER.equals(m.role()) && !USER.equals(m.role()))
        .forEach(m -> errors.add("members: " + m.email() + " needs the role developer or user"));
    errors.addAll(LlmFormDTO.validate(llms));
    errors.addAll(DataSourceFormDTO.validate(dataSources));
    return errors;
  }

  /** Who may ask its airlocks: every member, developers too. */
  public List<String> emails() {
    return members.stream().map(Member::email).toList();
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }
}
