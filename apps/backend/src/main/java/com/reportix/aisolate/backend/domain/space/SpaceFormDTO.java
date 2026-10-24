package com.reportix.aisolate.backend.domain.space;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import com.reportix.aisolate.config.Names;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What the admin fills in for a space: its identity, its developers (by the token's e-mail), who
 * edit its airlocks and deploy into it, the roles (the token's roles claim, exact) that may ask
 * every airlock of it, as may an airlock's own roles, and the LLMs and data sources its airlocks
 * share beside the global ones, each optional. Validated here; stored as the columns and the
 * definition JSON of a space.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpaceFormDTO(
    String id,
    String name,
    String description,
    List<String> developers,
    List<String> roles,
    List<LlmFormDTO> llms,
    List<DataSourceFormDTO> dataSources) {

  public SpaceFormDTO {
    // The label is optional: without one the id is the name every reader shows.
    name = name == null || name.isBlank() ? id : name;
    description = strip(description);
    developers = AirlockFormDTO.stripEach(developers);
    roles = AirlockFormDTO.asTyped(roles);
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
            developers,
            roles,
            llms,
            dataSources);
  }

  public SpaceFormDTO withDataSources(List<DataSourceFormDTO> rows) {
    return new SpaceFormDTO(id, name, description, developers, roles, llms, rows);
  }

  public SpaceFormDTO withLlms(List<LlmFormDTO> rows) {
    return new SpaceFormDTO(id, name, description, developers, roles, rows, dataSources);
  }

  /** One message per problem, each naming the field it is about. Empty means valid. */
  public List<String> validate() {
    List<String> errors = new ArrayList<>();
    if (id == null || !Names.SLUG.matcher(id).matches()) {
      errors.add("id: " + Names.SLUG_RULE);
    }
    Names.emailProblems(developers).forEach(problem -> errors.add("developers: " + problem));
    AirlockFormDTO.checkRoles(roles, errors);
    errors.addAll(LlmFormDTO.validate(llms));
    errors.addAll(DataSourceFormDTO.validate(dataSources));
    return errors;
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }
}
