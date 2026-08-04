package com.reportix.aisolate.toolserver.domain.rules;

/** One row condition an allow rule grants, with its bound value: {@code column = value}. */
public record RowPredicate(String column, Object value) {}
