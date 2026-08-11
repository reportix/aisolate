package com.reportix.aisolate.toolserver.infrastructure;

import com.reportix.aisolate.config.DataSourceLogin;
import com.reportix.aisolate.toolserver.domain.CustomerDataSources;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The airlock's data sources, one pool each, named by its id, through its read-only role. These are
 * the only databases this server connects to; it has no connection to AIsolate's own. The
 * connection decides nothing about authorization; the rules do, before any query runs.
 */
@Configuration
@EnableConfigurationProperties(CustomerDataSourceProperties.class)
public class DataSourceConfig {

  /**
   * Per statement, each under the airlock's 20 s MCP request timeout; a tool that runs several (the
   * identity join, then the query) can still outlast it, and the airlock then gives up first.
   */
  private static final long CONNECTION_TIMEOUT_MS = 5_000;

  private static final int QUERY_TIMEOUT_SECONDS = 12;

  /**
   * Above the query timeout, so that one cancels a slow query; after a silent network loss its
   * cancel request never arrives, and only this frees the thread.
   */
  private static final int SOCKET_TIMEOUT_SECONDS = 15;

  private final List<HikariDataSource> pools = new ArrayList<>();

  @Bean
  CustomerDataSources customerDataSources(CustomerDataSourceProperties properties) {
    DataSourceLogin.check(properties.datasource());
    Map<String, JdbcClient> byId = new LinkedHashMap<>();
    for (DataSourceLogin entry : properties.datasource()) {
      HikariDataSource pool = new HikariDataSource();
      pool.setPoolName(entry.id());
      pool.setJdbcUrl(entry.url());
      pool.setUsername(entry.username());
      pool.setPassword(entry.password());
      pool.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
      // Read-only whatever the role allows; pgjdbc's default skips it in autocommit.
      pool.setReadOnly(true);
      pool.addDataSourceProperty("readOnlyMode", "always");
      pool.addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
      pools.add(pool);
      JdbcTemplate jdbc = new JdbcTemplate(pool);
      jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
      byId.put(entry.id(), JdbcClient.create(jdbc));
    }
    return new CustomerDataSources(byId);
  }

  /** The pools are no beans of their own, so the context would not close them. */
  @PreDestroy
  void closePools() {
    pools.forEach(HikariDataSource::close);
  }
}
