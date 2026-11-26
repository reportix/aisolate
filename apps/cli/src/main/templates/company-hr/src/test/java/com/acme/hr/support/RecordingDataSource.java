package com.acme.hr.support;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiFunction;
import javax.sql.DataSource;

/**
 * A database that answers a query with the rows filed under its last bound value, a document's
 * title; one without bound values with the employees whose contracts are filed. Records the values
 * each statement was run with.
 */
public final class RecordingDataSource {

  private static final String TITLE = "Employment Contract ";

  public final List<List<Object>> executed = new ArrayList<>();
  private final Map<String, List<Object[]>> rows;

  /** The rows by title, in the order filed, one array each, read by column position from 1. */
  public RecordingDataSource(Map<String, List<Object[]>> rows) {
    this.rows = new LinkedHashMap<>(rows);
  }

  public DataSource dataSource() {
    return proxy(
        DataSource.class, (method, args) -> method.equals("getConnection") ? connection() : null);
  }

  private Connection connection() {
    return proxy(
        Connection.class, (method, args) -> method.equals("prepareStatement") ? statement() : null);
  }

  private PreparedStatement statement() {
    TreeMap<Integer, Object> params = new TreeMap<>();
    return proxy(
        PreparedStatement.class,
        (method, args) ->
            switch (method) {
              case "setString", "setDouble", "setInt", "setLong" -> {
                params.put((Integer) args[0], args[1]);
                yield null;
              }
              case "executeQuery" -> {
                executed.add(new ArrayList<>(params.values()));
                yield resultSet(answer(params));
              }
              case "executeUpdate" -> {
                executed.add(new ArrayList<>(params.values()));
                yield 0;
              }
              default -> null;
            });
  }

  private List<Object[]> answer(TreeMap<Integer, Object> params) {
    if (params.isEmpty()) {
      return rows.keySet().stream()
          .map(title -> new Object[] {title.replaceFirst("^" + TITLE, "")})
          .toList();
    }
    return rows.getOrDefault(params.lastEntry().getValue(), List.of());
  }

  private static ResultSet resultSet(List<Object[]> rows) {
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
