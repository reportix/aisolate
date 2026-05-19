package com.reportix.aisolate.sdk;

import java.util.List;
import java.util.Map;

/**
 * What a governed query returns and a tool hands back to the model: the rows, their columns, and
 * the evidence of how they were obtained (the rule version, the rules that matched, a hash of the
 * SQL shape) and what its columns mean (the labels). {@code rows} reach the model only; AIsolate
 * strips them before anything reaches the client.
 *
 * @param labels what the columns mean, from the entity; null for a result a tool built itself,
 *     whose figures are then shown without a subject, a period or a unit
 */
public record Result(
    String entity,
    int rowCount,
    List<String> columns,
    List<Map<String, Object>> rows,
    String ruleVersion,
    List<String> rules,
    String sqlHash,
    Labels labels) {

  /** A result without labels. */
  public Result(
      String entity,
      int rowCount,
      List<String> columns,
      List<Map<String, Object>> rows,
      String ruleVersion,
      List<String> rules,
      String sqlHash) {
    this(entity, rowCount, columns, rows, ruleVersion, rules, sqlHash, null);
  }

  /** A denied query: indistinguishable from "there is nothing to see". */
  public static Result empty(String entity, String ruleVersion) {
    return new Result(entity, 0, List.of(), List.of(), ruleVersion, List.of(), "");
  }

  /**
   * A result's columns as the entity declares them ({@link Entity#subjectName}, {@link
   * Entity#period}, {@link Column#unit}), each only when the result has that column.
   *
   * @param rowKey the column identifying a row; for an aggregate the grouping column, or null
   * @param subject the column naming whom a row is about; null when the result has none
   * @param period the column saying which period a row is about; null when it has none
   * @param units each column's fixed unit, by column
   * @param unitColumns the column holding each row's unit, by column
   * @param decimals each column's declared decimals ({@link Column#decimals}), by column
   * @param masked the columns the rules masked: their values arrive as {@code ****} and never stand
   *     for a figure
   * @param function the aggregate function ({@code sum}, {@code avg}, ...) when each row is a
   *     group's value, about no one subject; null for a read
   * @param facts the readable columns that are a {@link Column#fact}, in column order: a value of
   *     one typed into an answer's text is unsourced; empty for an aggregate
   */
  public record Labels(
      String rowKey,
      String subject,
      String period,
      Map<String, String> units,
      Map<String, String> unitColumns,
      Map<String, Integer> decimals,
      List<String> masked,
      String function,
      List<String> facts) {

    public Labels {
      units = units == null ? Map.of() : Map.copyOf(units);
      unitColumns = unitColumns == null ? Map.of() : Map.copyOf(unitColumns);
      decimals = decimals == null ? Map.of() : Map.copyOf(decimals);
      masked = masked == null ? List.of() : List.copyOf(masked);
      facts = facts == null ? List.of() : List.copyOf(facts);
    }

    /** Labels without facts: the constructor before facts, so older plugin code compiles. */
    public Labels(
        String rowKey,
        String subject,
        String period,
        Map<String, String> units,
        Map<String, String> unitColumns,
        Map<String, Integer> decimals,
        List<String> masked,
        String function) {
      this(rowKey, subject, period, units, unitColumns, decimals, masked, function, List.of());
    }
  }
}
