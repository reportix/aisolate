package com.reportix.aisolate.toolserver.application;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Why a tool call could not read its data, in the words the model gets and the code the airlock
 * passes on to the chat ({@code _meta.error}); conformance/cases/errors/data-source.json.
 */
public enum ToolError {
  LOGIN_REFUSED("login_refused", "The data source refused the login"),
  UNREACHABLE("unreachable", "The data source could not be reached"),
  FAILED("failed", "The tool could not complete the request");

  private final String code;
  private final String text;

  ToolError(String code, String text) {
    this.code = code;
    this.text = text;
  }

  public String code() {
    return code;
  }

  public String text() {
    return text;
  }

  /**
   * A SQLSTATE of class 28 anywhere in the failure is a refused login; else one of class 08 or a
   * network failure makes the database unreachable. The driver nests its causes in both {@code
   * getCause} and {@code getNextException}, and a pool's timeout wraps the login it last tried.
   */
  public static ToolError of(Throwable failure) {
    ToolError found = FAILED;
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> todo = new ArrayDeque<>();
    todo.push(failure);
    while (!todo.isEmpty()) {
      Throwable cause = todo.pop();
      if (!seen.add(cause)) {
        continue;
      }
      String state = cause instanceof SQLException sql ? sql.getSQLState() : null;
      if (state != null && state.startsWith("28")) {
        return LOGIN_REFUSED;
      }
      if (state != null && state.startsWith("08")
          || cause instanceof SQLTransientConnectionException
          || cause instanceof ConnectException
          || cause instanceof UnknownHostException
          || cause instanceof NoRouteToHostException
          || cause instanceof SocketTimeoutException) {
        found = UNREACHABLE;
      }
      if (cause.getCause() != null) {
        todo.push(cause.getCause());
      }
      if (cause instanceof SQLException sql && sql.getNextException() != null) {
        todo.push(sql.getNextException());
      }
    }
    return found;
  }
}
