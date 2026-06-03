package com.reportix.aisolate.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One data source as the controller renders it into both pods' env: {@code
 * AISOLATE_DATASOURCE_<i>_ID}, {@code _URL}, {@code _USERNAME}, {@code _PASSWORD}, bound as {@code
 * aisolate.datasource[i].*}; the password is the read-only role's its Secret holds.
 */
public record DataSourceLogin(String id, String url, String username, String password) {

  /** Without the password: settings end up in logs and failure messages. */
  @Override
  public String toString() {
    return "DataSourceLogin[id=" + id + ", url=" + url + ", username=" + username + "]";
  }

  /**
   * Refused at the start: a lazy connection would otherwise fail at its first call, and a second
   * entry with one id would silently replace the first.
   */
  public static void check(List<DataSourceLogin> logins) {
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < logins.size(); i++) {
      DataSourceLogin login = logins.get(i);
      if (login.id() == null || login.id().isBlank()) {
        throw new IllegalStateException("AISOLATE_DATASOURCE_" + i + "_ID is blank");
      }
      if (login.url() == null || login.url().isBlank()) {
        throw new IllegalStateException("AISOLATE_DATASOURCE_" + i + "_URL is blank");
      }
      if (!ids.add(login.id())) {
        throw new IllegalStateException("data source " + login.id() + " is defined twice");
      }
    }
  }
}
