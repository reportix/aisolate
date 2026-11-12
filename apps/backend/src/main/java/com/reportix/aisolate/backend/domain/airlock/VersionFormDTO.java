package com.reportix.aisolate.backend.domain.airlock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reportix.aisolate.config.Names;
import java.util.ArrayList;
import java.util.List;
import lombok.With;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One airlock version as the developer fills it in, in the order an exchange runs: its name and
 * description; before the model, the context preparators and the memory handlers; the LLM that
 * answers, by id, with the prompt and the tools the model may call; after the model, the verifiers;
 * and its own LLMs, keyless. Who may ask, and the data sources, are its airlock's. A new version
 * needs its name and its LLM. Tools and handlers are its code's, listed by tool name and class
 * simple name, and checked against the code by {@link AirlockService}. Stored flat as the version's
 * definition JSON, the name and description in their own columns.
 */
@With
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record VersionFormDTO(
    String version,
    String description,
    List<String> contextPreparators,
    List<String> memoryHandlers,
    String llm,
    String prompt,
    List<String> tools,
    List<String> verifiers,
    List<LlmFormDTO> llms) {

  /** What a version tells the model when its developer wrote no prompt. */
  public static final String DEFAULT_PROMPT =
      "You are a governed assistant. Answer only from tool evidence and the documents provided to"
          + " you, and cite them as [ev-n]. When nothing answers, reply exactly: I don't know.";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public VersionFormDTO {
    // Unset strings read "" on the wire: the AIsolate UI relies on it.
    version = strip(version);
    description = strip(description);
    contextPreparators = copy(contextPreparators);
    memoryHandlers = copy(memoryHandlers);
    llm = strip(llm);
    prompt = prompt == null ? "" : prompt;
    tools = copy(tools);
    verifiers = copy(verifiers);
    // A Secret name is AIsolate's to give, and a version's models get none: no key comes with code.
    llms = LlmFormDTO.clean(llms).stream().map(row -> row.withSecretRef("")).toList();
  }

  /** The stored version, shaped as the form the AIsolate UI edits. */
  public static VersionFormDTO from(AirlockVersionRepository.Row row, JsonMapper json) {
    ObjectNode definition = ((ObjectNode) row.definition()).deepCopy();
    definition.put("version", row.version());
    definition.put("description", row.description());
    return json.treeToValue(definition, VersionFormDTO.class);
  }

  /** The stored definition: the form without its name and description. */
  public String toDefinitionJson(JsonMapper json) {
    ObjectNode definition = json.valueToTree(this);
    definition.remove(List.of("version", "description"));
    return json.writeValueAsString(definition);
  }

  /** A version saved without a prompt tells the model {@link #DEFAULT_PROMPT}. */
  public VersionFormDTO withDefaultPrompt() {
    return prompt.isBlank() ? withPrompt(DEFAULT_PROMPT) : this;
  }

  /** One message per problem, each naming the field it is about, never its value. */
  public List<String> validate() {
    List<String> errors = new ArrayList<>();
    if (version.isEmpty()) {
      errors.add("version: required");
    } else if (!Names.VERSION_LABEL.matcher(version).matches()) {
      errors.add("version: " + Names.VERSION_RULE);
    } else if (version.equals("new")) {
      // The AIsolate UI's create page is .../versions/new.
      errors.add("version: new is reserved");
    }
    checkNames("context_preparators", contextPreparators, errors);
    checkNames("memory_handlers", memoryHandlers, errors);
    if (llm.isEmpty()) {
      errors.add("llm: name the model that answers, for example granite");
    }
    checkNames("tools", tools, errors);
    checkNames("verifiers", verifiers, errors);
    errors.addAll(LlmFormDTO.validate(llms));
    for (int i = 0; i < llms.size(); i++) {
      if (llms.get(i).bringsCredentials()) {
        errors.add(
            "llms["
                + i
                + "].api_key: a version's model takes no API key; define the model on its airlock");
      }
    }
    return errors;
  }

  /** The form as the API returns it, so no credential reaches a log line through this record. */
  @Override
  public String toString() {
    return "VersionFormDTO" + JSON.writeValueAsString(this);
  }

  private static void checkNames(String field, List<String> names, List<String> errors) {
    Names.problems("name", names).forEach(problem -> errors.add(field + ": " + problem));
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }

  private static List<String> copy(List<String> names) {
    return names == null ? List.of() : List.copyOf(names);
  }
}
