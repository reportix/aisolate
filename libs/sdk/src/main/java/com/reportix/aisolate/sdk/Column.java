package com.reportix.aisolate.sdk;

/**
 * One column of an {@link Entity}: its model-facing name and the SQL expression behind it.
 *
 * @param unit what every value of the column is in, for example {@code EUR}, {@code ng/L}, {@code
 *     mmHg}, {@code %} or {@code days}; null without one. AIsolate shows a cited value with it.
 * @param unitColumn the column of the same entity holding each row's unit (a currency column, a lab
 *     result's unit); null when the unit is fixed or there is none
 * @param decimals how many decimals AIsolate shows of a value of the column, an aggregate of it
 *     included; null for the value's own (an average: two). The exact value is kept as returned.
 * @param fact a text column whose values, typed into an answer's text instead of cited, are shown
 *     as unsourced (a drug, a diagnosis). A NUMBER column refuses it: every typed number is checked
 *     anyway.
 */
public record Column(
    String name,
    String expression,
    Type type,
    String unit,
    String unitColumn,
    Integer decimals,
    boolean fact) {

  public enum Type {
    TEXT,
    NUMBER
  }

  public Column {
    unit = unit == null || unit.isBlank() ? null : unit;
    unitColumn = unitColumn == null || unitColumn.isBlank() ? null : unitColumn;
    if (decimals != null && decimals < 0) {
      throw new IllegalArgumentException("decimals of column " + name + " cannot be negative");
    }
    if (fact && type != Type.TEXT) {
      throw new IllegalArgumentException(
          "column " + name + " is a number: only a text column can be a fact");
    }
  }

  /** A column without a unit. */
  public Column(String name, String expression, Type type) {
    this(name, expression, type, null, null, null, false);
  }

  /** A column that is no fact: the constructor before facts, so older plugin code compiles. */
  public Column(
      String name, String expression, Type type, String unit, String unitColumn, Integer decimals) {
    this(name, expression, type, unit, unitColumn, decimals, false);
  }

  /** The same column, every value in {@code unit}: {@code EUR}, {@code ng/L}, {@code %}. */
  public Column withUnit(String unit) {
    return new Column(name, expression, type, unit, null, decimals, fact);
  }

  /**
   * The same column, each row's unit read from {@code column} of the same entity: {@code
   * withUnitFrom("currency")} beside an amount, {@code withUnitFrom("unit")} beside a lab value.
   */
  public Column withUnitFrom(String column) {
    return new Column(name, expression, type, null, column, decimals, fact);
  }

  /** The same column, shown with {@code decimals} decimals: {@code 2} for money, cents kept. */
  public Column withDecimals(int decimals) {
    return new Column(name, expression, type, unit, unitColumn, decimals, fact);
  }

  /** The same column, a fact: a value of it typed into an answer's text is shown as unsourced. */
  public Column asFact() {
    return new Column(name, expression, type, unit, unitColumn, decimals, true);
  }
}
