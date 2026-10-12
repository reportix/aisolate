package com.aldermoor.ehr.support;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiFunction;
import javax.sql.DataSource;

/**
 * A database that records every statement a handler prepares and the values it binds, and answers
 * every query with the rows it was given, none by default: enough to check what a handler asks and
 * what it does with the answer, without a database.
 */
public final class RecordingDataSource {

  /** One prepared statement: its SQL and its bound values by position. */
  public record Statement(String sql, Map<Integer, Object> params) {}

  public final List<Statement> statements = new ArrayList<>();
  private final List<Object[]> rows;

  /** A database with no rows. */
  public RecordingDataSource() {
    this(List.of());
  }

  /** Every query returns these rows, one array each, read by column position from 1. */
  public RecordingDataSource(List<Object[]> rows) {
    this.rows = rows;
  }

  public DataSource dataSource() {
    return proxy(
        DataSource.class, (method, args) -> method.equals("getConnection") ? connection() : null);
  }

  private Connection connection() {
    return proxy(
        Connection.class,
        (method, args) -> {
          if (!method.equals("prepareStatement")) {
            return null;
          }
          Statement statement = new Statement((String) args[0], new TreeMap<>());
          statements.add(statement);
          return statement(statement);
        });
  }

  private PreparedStatement statement(Statement statement) {
    return proxy(
        PreparedStatement.class,
        (method, args) ->
            switch (method) {
              case "setString", "setDouble", "setInt", "setLong" -> {
                statement.params().put((Integer) args[0], args[1]);
                yield null;
              }
              case "executeQuery" -> resultSet();
              case "executeUpdate" -> 0;
              default -> null;
            });
  }

  private ResultSet resultSet() {
    Iterator<Object[]> pending = rows.iterator();
    Object[][] current = {null};
    return proxy(
        ResultSet.class,
        (method, args) ->
            switch (method) {
              case "next" -> {
                current[0] = pending.hasNext() ? pending.next() : null;
                yield current[0] != null;
              }
              case "getString" -> Objects.toString(current[0][(Integer) args[0] - 1], null);
              default -> null;
            });
  }

  private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (self, method, args) -> handler.apply(method.getName(), args)));
  }
}
