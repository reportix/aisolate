package com.reportix.aisolate.config.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * One access rule, as a jar's rule-set file carries it. The backend checks it; the tool server
 * evaluates it.
 */
public record Rule(
    String name,
    Map<String, String> principals,
    List<Action> actions,
    Effect effect,
    @JsonProperty("row_filter") String rowFilter,
    Map<String, ColumnAction> columns,
    Aggregation aggregation) {

  /** What a rule allows a tool to do with the entity's rows. */
  public enum Action {
    @JsonProperty("read")
    READ,
    @JsonProperty("aggregate")
    AGGREGATE
  }

  public enum Effect {
    @JsonProperty("allow")
    ALLOW,
    @JsonProperty("deny")
    DENY
  }

  public enum ColumnAction {
    @JsonProperty("hide")
    HIDE,
    @JsonProperty("mask")
    MASK
  }

  public record Aggregation(@JsonProperty("min_group_size") Integer minGroupSize) {}

  public Rule {
    principals = principals == null ? Map.of() : Map.copyOf(principals);
    actions = actions == null ? List.of() : List.copyOf(actions);
    columns = columns == null ? Map.of() : Map.copyOf(columns);
    for (String key : principals.keySet()) {
      if (!key.equals("role")) {
        throw new IllegalArgumentException(
            "rule "
                + name
                + ": unknown principals selector '"
                + key
                + "' (only 'role' is supported)");
      }
    }
    // A rule with no effect, role or action would be read as an allow, or as a deny that never
    // matches: both wider than what was written.
    if (effect == null) {
      throw new IllegalArgumentException("rule " + name + ": effect is required (allow or deny)");
    }
    if (principals.get("role") == null || principals.get("role").isBlank()) {
      throw new IllegalArgumentException("rule " + name + ": principals.role is required");
    }
    if (actions.isEmpty()) {
      throw new IllegalArgumentException("rule " + name + ": actions must name read or aggregate");
    }
    // The evaluator drops every row on a matching deny: anything narrower would not be what runs.
    if (effect == Effect.DENY && (rowFilter != null || !columns.isEmpty() || aggregation != null)) {
      throw new IllegalArgumentException(
          "rule "
              + name
              + ": a deny takes no row_filter, columns or aggregation; it drops every row for its"
              + " role and actions");
    }
    if (effect == Effect.ALLOW) {
      RowFilter.parse(rowFilter); // fail at load time, not at query time
    }
    if (aggregation != null
        && aggregation.minGroupSize() != null
        && aggregation.minGroupSize() < 1) {
      throw new IllegalArgumentException(
          "rule " + name + ": aggregation.min_group_size must be at least 1");
    }
  }

  public boolean appliesTo(String role, Action action) {
    return role.equals(principals.get("role")) && actions.contains(action);
  }
}
