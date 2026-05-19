package com.reportix.aisolate.sdk;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Allow-list of what a tool may query. Column names arriving from the model are resolved here or
 * rejected; SQL is built from the catalog's own expressions, never from model text. Each plugin
 * declares its entities next to the data it serves.
 *
 * <p>The subject's name, the period and the columns' units are a row's labels: AIsolate shows each
 * figure an answer cites with them ("Elena Varga · 2026 · EUR") and checks the model's own claim of
 * whom and which period a figure is about against them.
 *
 * @param rowKey the column that orders the rows
 * @param subject the column naming whom a row is about, for example {@code patient_id} when one
 *     patient has many readings; the row key when every row is about one person. A minimum group
 *     size counts its distinct values, never rows, and when its name is the plugin's {@link
 *     ToolPlugin#identityAttribute} a row whose value is the asking user's is marked {@code self}
 * @param subjectName the column naming the subject for a reader, {@code full_name} beside an {@code
 *     employee_id}; null when the entity names none, and its rows carry no subject label (an id is
 *     no name to check a claim against)
 * @param period the column saying which period a row is about ({@code year}, {@code period}, a
 *     date); null when its rows are about no period
 */
public record Entity(
    String name,
    String from,
    String rowKey,
    Map<String, Column> columns,
    String subject,
    String subjectName,
    String period) {

  public Entity {
    // The key is what the model and the rules use: a column filed under another name would answer
    // to a name its own record does not carry.
    columns.forEach(
        (key, column) -> {
          if (!key.equals(column.name())) {
            throw new IllegalArgumentException(
                "entity " + name + ": column " + column.name() + " is keyed " + key);
          }
        });
    // Declaration order is the result's column order.
    columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    subject = subject == null || subject.isBlank() ? rowKey : subject;
    subjectName = subjectName == null || subjectName.isBlank() ? null : subjectName;
    period = period == null || period.isBlank() ? null : period;
    // A label naming no column would label every row with nothing, silently.
    requireDeclared(name, columns, subjectName, "subject name");
    requireDeclared(name, columns, period, "period");
    for (Column column : columns.values()) {
      requireDeclared(name, columns, column.unitColumn(), "unit of " + column.name());
    }
  }

  /** Each row about whom {@code subject} names, labelled by it, about no period. */
  public Entity(
      String name, String from, String rowKey, Map<String, Column> columns, String subject) {
    this(name, from, rowKey, columns, subject, null, null);
  }

  /** Each row about one person, the one its row key names. */
  public Entity(String name, String from, String rowKey, Map<String, Column> columns) {
    this(name, from, rowKey, columns, rowKey);
  }

  public Entity(String name, String from, String rowKey, Column... columns) {
    this(
        name,
        from,
        rowKey,
        Arrays.stream(columns)
            .collect(
                Collectors.toMap(
                    Column::name,
                    Function.identity(),
                    (a, b) -> {
                      // Keeping either would silently drop the other's expression.
                      throw new IllegalArgumentException(
                          "entity " + name + ": column " + a.name() + " is declared twice");
                    },
                    LinkedHashMap::new)));
  }

  /** The same entity, its rows about whom {@code column} names ({@link #subject}). */
  public Entity withSubject(String column) {
    return new Entity(name, from, rowKey, columns, column, subjectName, period);
  }

  /** The same entity, its subject named for a reader by {@code column} ({@link #subjectName}). */
  public Entity withSubjectName(String column) {
    return new Entity(name, from, rowKey, columns, subject, column, period);
  }

  /** The same entity, each row about the period {@code column} holds ({@link #period}). */
  public Entity withPeriod(String column) {
    return new Entity(name, from, rowKey, columns, subject, subjectName, column);
  }

  public Column column(String name) {
    Column column = columns.get(name);
    if (column == null) {
      throw new IllegalArgumentException("Unknown column: " + name);
    }
    return column;
  }

  /** Columns that may appear in the result (masked ones arrive as {@code ****}). */
  public Set<String> visibleColumns(Set<String> hidden) {
    Set<String> visible = new LinkedHashSet<>(columns.keySet());
    visible.removeAll(hidden);
    return visible;
  }

  /**
   * Columns the model may filter, group or aggregate on. A masked column is excluded too: a
   * predicate on it would let the model recover the masked value.
   */
  public Set<String> queryableColumns(Set<String> hidden, Set<String> masked) {
    Set<String> queryable = visibleColumns(hidden);
    queryable.removeAll(masked);
    return queryable;
  }

  private static void requireDeclared(
      String entity, Map<String, Column> columns, String column, String what) {
    if (column != null && !columns.containsKey(column)) {
      throw new IllegalArgumentException(
          "entity " + entity + ": " + what + " " + column + " is not one of its columns");
    }
  }
}
