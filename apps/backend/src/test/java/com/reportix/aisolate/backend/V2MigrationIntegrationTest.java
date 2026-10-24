package com.reportix.aisolate.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.support.Containers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An install's V1 rows as V2 leaves them: developers kept, nobody admitted until roles are set. */
class V2MigrationIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DATABASE = "v2_migration";

  @Test
  void givenV1Definitions_whenMigrated_thenDevelopersKeptRolesEmpty() throws SQLException {
    // Arrange
    String url = freshDatabase();
    migrate(url, "1");
    execute(
        url,
        """
        INSERT INTO spaces (id, name, definition) VALUES
          ('hr', 'HR', '{"members": [{"email": "eric@example.com", "role": "user"},
                                     {"email": "Jasmine@Example.com", "role": "developer"},
                                     {"email": "nora@example.com", "role": "developer"}],
                         "llms": [{"id": "granite"}], "data_sources": []}'),
          ('sales', 'Sales', '{"members": [], "llms": [], "data_sources": []}'),
          ('bare', 'Bare', '{}');
        INSERT INTO airlocks (id, space_id, name, definition) VALUES
          ('hr-bot', 'hr', 'HR bot', '{"users": ["eric@example.com"], "callers": ["*"],
                                       "clients": ["batch"], "llm": "granite"}');
        """);

    // Act
    migrate(url, "2");

    // Assert
    Map<String, JsonNode> spaces = definitions(url, "spaces");
    assertThat(spaces.get("hr"))
        .isEqualTo(
            JSON.readTree(
                """
                {"llms": [{"id": "granite"}], "data_sources": [],
                 "developers": ["Jasmine@Example.com", "nora@example.com"], "roles": []}\
                """));
    assertThat(spaces.get("sales"))
        .isEqualTo(
            JSON.readTree(
                """
                {"llms": [], "data_sources": [], "developers": [], "roles": []}\
                """));
    assertThat(spaces.get("bare")).isEqualTo(JSON.readTree("{\"developers\": [], \"roles\": []}"));
    assertThat(definitions(url, "airlocks").get("hr-bot"))
        .isEqualTo(
            JSON.readTree(
                """
                {"roles": [], "callers": ["*"], "clients": ["batch"], "llm": "granite"}\
                """));
  }

  /** A database of its own in the shared container, so the other classes' seed never meets it. */
  private static String freshDatabase() throws SQLException {
    var postgres = Containers.aisolate();
    execute(postgres.getJdbcUrl(), "DROP DATABASE IF EXISTS " + DATABASE);
    execute(postgres.getJdbcUrl(), "CREATE DATABASE " + DATABASE);
    return "jdbc:postgresql://"
        + postgres.getHost()
        + ":"
        + postgres.getMappedPort(5432)
        + "/"
        + DATABASE;
  }

  private static void migrate(String url, String target) {
    Flyway.configure()
        .dataSource(url, "aisolate", "aisolate")
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static void execute(String url, String sql) throws SQLException {
    try (Connection db = DriverManager.getConnection(url, "aisolate", "aisolate");
        Statement statement = db.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Map<String, JsonNode> definitions(String url, String table) throws SQLException {
    Map<String, JsonNode> definitions = new LinkedHashMap<>();
    try (Connection db = DriverManager.getConnection(url, "aisolate", "aisolate");
        Statement statement = db.createStatement();
        ResultSet rows = statement.executeQuery("SELECT id, definition::text FROM " + table)) {
      while (rows.next()) {
        definitions.put(rows.getString(1), JSON.readTree(rows.getString(2)));
      }
    }
    return definitions;
  }
}
