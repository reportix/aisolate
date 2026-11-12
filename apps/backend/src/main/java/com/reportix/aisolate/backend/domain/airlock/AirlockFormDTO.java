package com.reportix.aisolate.backend.domain.airlock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reportix.aisolate.config.Names;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import lombok.With;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

/**
 * An airlock as the developer fills it in: the id is its endpoint path; the name (its label, the id
 * when blank) and description are what the AIsolate UI shows; who may ask every version of it
 * besides its space's roles, by its own roles (the token's roles claim, exact), none at all
 * allowed, the OAuth clients they may call from (the token's azp, {@code *} for any), and the
 * machine clients admitted without an e-mail (the token's azp, exact); and its own LLMs and data
 * sources, beside the space's and the global ones, an id of theirs replacing it. What runs is its
 * versions' ({@link VersionFormDTO}). Stored flat as the airlock's definition JSON, the id, name
 * and description in their own columns.
 */
@With
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AirlockFormDTO(
    String id,
    String name,
    String description,
    List<String> roles,
    List<String> callers,
    List<String> clients,
    List<LlmFormDTO> llms,
    List<DataSourceFormDTO> dataSources) {

  /** The longest id {@link Names#SLUG} allows. */
  public static final int MAX_ID = 41;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public AirlockFormDTO {
    // The label is optional: without one the id is the name every reader shows.
    name = name == null || name.isBlank() ? id : name;
    // Unset strings read "" on the wire: the AIsolate UI relies on it.
    description = strip(description);
    roles = asTyped(roles);
    callers = stripEach(callers);
    clients = stripEach(clients);
    llms = LlmFormDTO.clean(llms);
    dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
  }

  /**
   * The id of a space or airlock saved without one: the name's slug, behind {@code prefix-} when
   * too short or not starting with a letter, cut to {@code max}, then -2, -3… while {@code taken}
   * or {@code new}, which the AIsolate UI's create pages are at.
   */
  public static String deriveId(String name, String prefix, int max, Predicate<String> used) {
    Predicate<String> taken = used.or("new"::equals);
    String slug =
        name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (!slug.matches("[a-z].+")) {
      slug = prefix + "-" + slug;
    }
    String base = cut(slug, max);
    String id = base;
    for (int n = 2; taken.test(id); n++) {
      String suffix = "-" + n;
      id = cut(base, max - suffix.length()) + suffix;
    }
    return id;
  }

  private static String cut(String slug, int max) {
    return slug.substring(0, Math.min(slug.length(), max)).replaceAll("-+$", "");
  }

  /**
   * Without an id, a free one from the name ({@link #deriveId}), short enough that the space's
   * cluster names still fit a first version, {@code v1} ({@link Names#maxAirlockId}).
   */
  public AirlockFormDTO withIdFromName(String spaceId, Predicate<String> taken) {
    return hasIdOrNoName(id, name)
        ? this
        : withId(deriveId(name, "airlock", Math.min(MAX_ID, Names.maxAirlockId(spaceId)), taken));
  }

  /** A given id is kept; a blank name has none to give, so {@code validate} reports the id. */
  public static boolean hasIdOrNoName(String id, String name) {
    return (id != null && !id.isBlank()) || name == null || name.isBlank();
  }

  /** A new airlock admits any client until the developer narrows it. */
  public AirlockFormDTO withDefaultCallers() {
    return callers.isEmpty() ? withCallers(List.of("*")) : this;
  }

  /** One message per problem, each naming the field it is about, never its value. */
  public List<String> validate() {
    List<String> errors = new ArrayList<>();
    if (id == null || !Names.SLUG.matcher(id).matches()) {
      errors.add("id: " + Names.SLUG_RULE);
    }
    checkRoles(roles, errors);
    if (callers.isEmpty()) {
      errors.add("callers: " + Names.NO_CALLER);
    }
    Names.problems("client", callers).forEach(problem -> errors.add("callers: " + problem));
    Names.clientProblems(clients).forEach(problem -> errors.add("clients: " + problem));
    errors.addAll(LlmFormDTO.validate(llms));
    errors.addAll(DataSourceFormDTO.validate(dataSources));
    return errors;
  }

  /** A space's or an airlock's roles ({@link Names#roleProblems}); none is fine. */
  public static void checkRoles(List<String> roles, List<String> errors) {
    Names.roleProblems(roles).forEach(problem -> errors.add("roles: " + problem));
  }

  /** The form as the API returns it, so no credential reaches a log line through this record. */
  @Override
  public String toString() {
    return "AirlockFormDTO" + JSON.writeValueAsString(this);
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }

  /** A pasted e-mail or client id keeps no padding: it would never match the token's. */
  public static List<String> stripEach(List<String> entries) {
    return entries == null ? List.of() : entries.stream().map(AirlockFormDTO::strip).toList();
  }

  /** Roles as typed, padding and blanks kept for {@link #checkRoles} to refuse, not to fix. */
  public static List<String> asTyped(List<String> entries) {
    return entries == null ? List.of() : entries.stream().toList();
  }
}
