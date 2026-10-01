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
 * when blank) and description are what the AIsolate UI shows; who may ask it besides its space's
 * users, by its own users (the token's e-mail), none at all allowed, the OAuth clients they may
 * call from (the token's azp, {@code *} for any), and the machine clients admitted without an
 * e-mail (the token's azp, exact); then what runs, in the order an exchange runs: before the model,
 * the context preparators and the memory handlers; the LLM that answers, by id, with the prompt and
 * the tools the model may call; after the model, the verifiers; and its own LLMs and data sources,
 * beside the space's and the global ones, an id of theirs replacing it. The UI's form carries the
 * first part; {@code aisolate deploy} brings what runs, with the code. Tools and handlers are its
 * code's, listed by tool name and class simple name, and checked against the code by {@link
 * AirlockService}. Stored flat as the airlock's definition JSON, the id, name and description in
 * their own columns.
 */
@With
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AirlockFormDTO(
    String id,
    String name,
    String description,
    List<String> users,
    List<String> callers,
    List<String> clients,
    List<String> contextPreparators,
    List<String> memoryHandlers,
    String llm,
    String prompt,
    List<String> tools,
    List<String> verifiers,
    List<LlmFormDTO> llms,
    List<DataSourceFormDTO> dataSources) {

  /** What an airlock tells the model when its developer wrote no prompt. */
  public static final String DEFAULT_PROMPT =
      "You are a governed assistant. Answer only from tool evidence and the documents provided to"
          + " you, and cite them as [ev-n]. When nothing answers, reply exactly: I don't know.";

  /** The longest id {@link Names#SLUG} allows. */
  public static final int MAX_ID = 41;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public AirlockFormDTO {
    // The label is optional: without one the id is the name every reader shows.
    name = name == null || name.isBlank() ? id : name;
    // Unset strings read "" on the wire: the AIsolate UI relies on it.
    description = strip(description);
    users = stripEach(users);
    callers = stripEach(callers);
    clients = stripEach(clients);
    contextPreparators = copy(contextPreparators);
    memoryHandlers = copy(memoryHandlers);
    llm = strip(llm);
    prompt = prompt == null ? "" : prompt;
    tools = copy(tools);
    verifiers = copy(verifiers);
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
   * cluster names still fit ({@link Names#maxAirlockId}).
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

  /**
   * This form with what runs as {@code stored} holds it: the AIsolate UI's form never changes the
   * handlers, the LLM, the prompt or the tools, which {@code aisolate deploy} brings with the code.
   *
   * @param stored null for none, a new airlock's
   */
  public AirlockFormDTO withRunOf(AirlockFormDTO stored) {
    return withContextPreparators(stored == null ? null : stored.contextPreparators())
        .withMemoryHandlers(stored == null ? null : stored.memoryHandlers())
        .withLlm(stored == null ? null : stored.llm())
        .withPrompt(stored == null ? null : stored.prompt())
        .withTools(stored == null ? null : stored.tools())
        .withVerifiers(stored == null ? null : stored.verifiers());
  }

  /** An airlock with a model but no prompt tells the model {@link #DEFAULT_PROMPT}. */
  public AirlockFormDTO withDefaultPrompt() {
    return llm.isEmpty() || !prompt.isBlank() ? this : withPrompt(DEFAULT_PROMPT);
  }

  /** One message per problem, each naming the field it is about, never its value. */
  public List<String> validate() {
    List<String> errors = new ArrayList<>();
    if (id == null || !Names.SLUG.matcher(id).matches()) {
      errors.add("id: " + Names.SLUG_RULE);
    }
    checkUsers(users, errors);
    if (callers.isEmpty()) {
      errors.add("callers: " + Names.NO_CALLER);
    }
    Names.problems("client", callers).forEach(problem -> errors.add("callers: " + problem));
    Names.clientProblems(clients).forEach(problem -> errors.add("clients: " + problem));
    checkNames("context_preparators", contextPreparators, errors);
    checkNames("memory_handlers", memoryHandlers, errors);
    checkNames("tools", tools, errors);
    checkNames("verifiers", verifiers, errors);
    errors.addAll(LlmFormDTO.validate(llms));
    errors.addAll(DataSourceFormDTO.validate(dataSources));
    return errors;
  }

  /** The e-mails of a space's or an airlock's users ({@link Names#userProblems}); none is fine. */
  public static void checkUsers(List<String> users, List<String> errors) {
    Names.userProblems(users).forEach(problem -> errors.add("users: " + problem));
  }

  /** The form as the API returns it, so no credential reaches a log line through this record. */
  @Override
  public String toString() {
    return "AirlockFormDTO" + JSON.writeValueAsString(this);
  }

  private static void checkNames(String field, List<String> names, List<String> errors) {
    Names.problems("name", names).forEach(problem -> errors.add(field + ": " + problem));
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }

  /** A pasted e-mail or client id keeps no padding: it would never match the token's. */
  public static List<String> stripEach(List<String> entries) {
    return entries == null ? List.of() : entries.stream().map(AirlockFormDTO::strip).toList();
  }

  private static List<String> copy(List<String> names) {
    return names == null ? List.of() : List.copyOf(names);
  }
}
