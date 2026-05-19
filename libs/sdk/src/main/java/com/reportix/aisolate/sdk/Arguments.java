package com.reportix.aisolate.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shapes model-supplied arguments for the common tool inputs: a list of filters, a row limit, an
 * aggregate function. Columns and values are validated later by the server, against the entity and
 * the rules of the call.
 */
public final class Arguments {

  /** JSON Schema of a {@code filters} argument that {@link #filters(Object)} reads. */
  public static final Map<String, Object> FILTERS_SCHEMA =
      Map.of(
          "type",
          "array",
          "description",
          "Optional filters, combined with AND",
          "items",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of(
                  "column",
                  Map.of("type", "string", "description", "Column name from the entity"),
                  "op",
                  Map.of(
                      "type",
                      "string",
                      "enum",
                      List.of("eq", "neq", "gt", "gte", "lt", "lte", "ilike"),
                      "description",
                      "ilike = contains, case-insensitive; use it for names"),
                  "value",
                  Map.of("type", "string", "description", "Value to compare with")),
              "required",
              List.of("column", "op", "value")));

  public static final Map<String, Object> LIMIT_SCHEMA =
      Map.of("type", "integer", "description", "Maximum rows, at most 100");

  public static final Map<String, Object> FUNCTION_SCHEMA =
      Map.of("type", "string", "enum", List.of("sum", "avg", "count", "min", "max"));

  /** A tool's input schema: an object with these properties, some of them required. */
  public static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
    return Map.of("type", "object", "properties", properties, "required", required);
  }

  public static List<Filter> filters(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("filters must be a list");
    }
    List<Filter> filters = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException("each filter must be an object");
      }
      filters.add(
          new Filter(
              string(map.get("column"), "column"),
              enumValue(Filter.Op.class, map.get("op"), "op"),
              map.get("value")));
    }
    return filters;
  }

  public static int limit(Object raw, int fallback) {
    if (raw == null) {
      return fallback;
    }
    if (raw instanceof Number n) {
      return n.intValue();
    }
    throw new IllegalArgumentException("limit must be a number");
  }

  public static GovernedQuery.Fn function(Object raw) {
    return enumValue(GovernedQuery.Fn.class, raw, "function");
  }

  /** A required string argument. */
  public static String string(Object raw, String field) {
    if (!(raw instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return s;
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, Object raw, String field) {
    String text = string(raw, field);
    try {
      return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown " + field + ": " + text);
    }
  }

  private Arguments() {}
}
