package com.aldermoor.ehr.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds the grounded answer a verifier reads, as the airlock writes it: the SDK reads JSON and
 * does not write it.
 */
public final class GroundingJson {

  private GroundingJson() {}

  /** An object from name and value pairs, the members in that order. */
  public static Map<String, Object> object(Object... pairs) {
    Map<String, Object> members = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      members.put((String) pairs[i], pairs[i + 1]);
    }
    return members;
  }

  /** Where a cited value is from: what is null is left out, as the airlock leaves it out. */
  public static Map<String, Object> source(
      String evidence,
      String entity,
      String column,
      String rowKey,
      String subject,
      String period,
      String unit) {
    Map<String, Object> source =
        object(
            "evidence", evidence,
            "entity", entity,
            "column", column,
            "row_key", rowKey,
            "subject", subject,
            "period", period,
            "unit", unit);
    source.values().removeIf(Objects::isNull);
    return source;
  }

  /** A figure that is a cell, shown as {@code display}. */
  public static Map<String, Object> figure(String id, String display, Map<String, Object> source) {
    return figure(id, display, "sourced", "source", source);
  }

  /** A figure of any status, with the one member that says where it is from or how it is made. */
  public static Map<String, Object> figure(
      String id, String display, String status, String member, Map<String, Object> value) {
    return object("id", id, "display", display, "status", status, member, value);
  }

  /** One cell of a computation, shown as itself. */
  public static Map<String, Object> operand(String display, Map<String, Object> source) {
    Map<String, Object> operand = object("display", display);
    operand.putAll(source);
    return operand;
  }

  public static Map<String, Object> cell(String display, Map<String, Object> source) {
    return cell(display, "sourced", source);
  }

  public static Map<String, Object> cell(
      String display, String status, Map<String, Object> source) {
    return object("display", display, "status", status, "source", source);
  }

  public static Map<String, Object> table(List<List<Map<String, Object>>> rows) {
    return object("id", "t1", "status", "sourced", "rows", rows);
  }

  /** The grounded answer as far as a verifier reads it: the figures and the tables. */
  public static String grounded(List<?> figures, List<?> tables) {
    return json(object("figures", figures, "tables", tables));
  }

  /** The same with the segments, the answer's text pieces between its figures and tables. */
  public static String grounded(List<?> segments, List<?> figures, List<?> tables) {
    return json(object("segments", segments, "figures", figures, "tables", tables));
  }

  /** The same with the quotes of the passages, each in the text between the segments. */
  public static String grounded(List<?> segments, List<?> figures, List<?> tables, List<?> quotes) {
    return json(
        object("segments", segments, "figures", figures, "tables", tables, "quotes", quotes));
  }

  /** A quote of the first passage of ev-1: status {@code quoted}, or {@code mismatch}. */
  public static Map<String, Object> quote(String id, String status, String text) {
    return object(
        "id",
        id,
        "status",
        status,
        "evidence",
        "ev-1",
        "index",
        1,
        "title",
        "Cardiology note Julian Marek",
        "section",
        "2026-10-03",
        "text",
        text);
  }

  public static String json(Object value) {
    return switch (value) {
      case Map<?, ?> map ->
          map.entrySet().stream()
              .map(member -> json(member.getKey()) + ":" + json(member.getValue()))
              .collect(Collectors.joining(",", "{", "}"));
      case List<?> list ->
          list.stream().map(GroundingJson::json).collect(Collectors.joining(",", "[", "]"));
      case String text ->
          '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
      case null, default -> String.valueOf(value);
    };
  }
}
