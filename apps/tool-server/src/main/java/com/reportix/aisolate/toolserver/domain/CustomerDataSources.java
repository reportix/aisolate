package com.reportix.aisolate.toolserver.domain;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The databases this server may query, by data source id, each through its read-only role. */
public record CustomerDataSources(Map<String, JdbcClient> byId) {

  public CustomerDataSources {
    byId = Map.copyOf(byId);
  }

  /**
   * @throws IllegalStateException a plugin names a data source this server does not have
   */
  public JdbcClient forId(String dataSourceId) {
    JdbcClient db = byId.get(dataSourceId);
    if (db == null) {
      throw new IllegalStateException("unknown data source: " + dataSourceId);
    }
    return db;
  }
}
