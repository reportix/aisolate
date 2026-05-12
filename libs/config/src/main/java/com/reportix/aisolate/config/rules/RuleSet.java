package com.reportix.aisolate.config.rules;

import java.util.List;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The rules for one entity, as a jar's rule-set file carries them. An entity is a table a tool
 * plugin declares; the tool server applies the rule sets of the jars it loads.
 */
public record RuleSet(String entity, String classification, List<Rule> rules) {

  // Jackson 3 skips unknown keys and lets a repeated key win by default; either would silently
  // change a restriction.
  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final Set<String> KEYS = Set.of("entity", "classification", "rules");

  public RuleSet {
    rules = rules == null ? List.of() : List.copyOf(rules);
  }

  /**
   * One rule set as a developer's jar ships it, at {@code aisolate/rules/<entity>.json}. Anything
   * that is not exactly a rule set is refused, never read as a wider one: a rule set governs data.
   *
   * @throws IllegalArgumentException the JSON is not a rule set, a key other than entity,
   *     classification and rules, or no entity
   */
  public static RuleSet parse(String json) {
    JsonNode node;
    try {
      node = MAPPER.readTree(json);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid rules JSON: " + e.getMessage(), e);
    }
    if (!(node instanceof ObjectNode object)) {
      throw new IllegalArgumentException("a rule set is a JSON object");
    }
    for (String key : object.propertyNames()) {
      if (!KEYS.contains(key)) {
        throw new IllegalArgumentException(
            "unknown key '" + key + "' (a rule set has entity, classification and rules)");
      }
    }
    RuleSet ruleSet = fromJson(object.toString());
    if (ruleSet.entity() == null || ruleSet.entity().isBlank()) {
      throw new IllegalArgumentException("a rule set needs an entity");
    }
    return ruleSet;
  }

  private static RuleSet fromJson(String json) {
    try {
      return MAPPER.readValue(json, RuleSet.class);
    } catch (RuntimeException e) {
      // Jackson wraps the record's IllegalArgumentException; surface the original message.
      Throwable cause = e;
      while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) {
        cause = cause.getCause();
      }
      if (cause instanceof IllegalArgumentException iae) {
        throw iae;
      }
      throw new IllegalArgumentException("Invalid rules JSON: " + e.getMessage(), e);
    }
  }
}
