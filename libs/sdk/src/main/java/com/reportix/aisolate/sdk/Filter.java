package com.reportix.aisolate.sdk;

/**
 * One condition a tool adds to a governed query: {@code column op value}. The column must be one
 * the asking user may see; the value is always bound as a parameter, never interpolated.
 */
public record Filter(String column, Op op, Object value) {

  public enum Op {
    EQ,
    NEQ,
    GT,
    GTE,
    LT,
    LTE,
    ILIKE
  }
}
