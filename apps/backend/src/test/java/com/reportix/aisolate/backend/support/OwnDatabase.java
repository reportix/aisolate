package com.reportix.aisolate.backend.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A database of its own in the shared container, named by {@code aisolate.test.database}: for a
 * class whose writes must not meet the other classes' rows, the seed included.
 */
public final class OwnDatabase
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    String name = context.getEnvironment().getRequiredProperty("aisolate.test.database");
    var postgres = Containers.aisolate();
    try (Connection connection =
            DriverManager.getConnection(postgres.getJdbcUrl(), "aisolate", "aisolate");
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      if (!"42P04".equals(e.getSQLState())) { // 42P04: it exists already
        throw new IllegalStateException(e);
      }
    }
    // Runs after BackendTestConfig and merges into the same property source: this URL wins.
    TestPropertyValues.of(
            "spring.datasource.url=jdbc:postgresql://"
                + postgres.getHost()
                + ":"
                + postgres.getMappedPort(5432)
                + "/"
                + name)
        .applyTo(context);
  }
}
