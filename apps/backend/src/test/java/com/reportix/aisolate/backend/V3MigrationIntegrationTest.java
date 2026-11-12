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

/**
 * An install's V2 rows as V3 leaves them: what ran on an airlock becomes its version v1, with the
 * code; a never-deployed airlock gets none; the airlock keeps who may ask and what it defines.
 */
class V3MigrationIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DATABASE = "v3_migration";

  @Test
  void givenV2Airlocks_whenMigrated_thenEachThatRanIsItsV1AndTheAirlocksKeepTheRest()
      throws SQLException {
    // Arrange: deployed ran with code, named only names its model, idle was made in the UI
    String url = freshDatabase();
    migrate(url, "2");
    execute(
        url,
        """
        INSERT INTO spaces (id, name) VALUES ('hr', 'HR');
        INSERT INTO airlocks (id, space_id, name, definition, revision, created_by, jar_digest,
                              jar_size, jar_tools, jar_handlers, jar_rules, jar_language) VALUES
          ('deployed', 'hr', 'Deployed',
           '{"roles": ["employee"], "callers": ["*"], "clients": [],
             "llms": [{"id": "granite", "provider": "ollama", "url": "http://o", "model": "g",
                       "secret_ref": ""}],
             "data_sources": [{"id": "acme_hr", "url": "jdbc:x", "secret_ref": "hr-deployed-ds-1"}],
             "context_preparators": ["Handbook"], "memory_handlers": [], "llm": "granite",
             "prompt": "p", "tools": ["read_records"], "verifiers": []}',
           4, 'jasmine', 'sha256:ab', 10, '[{"name": "read_records"}]', '[]', '[]', 'java'),
          ('named', 'hr', 'Named',
           '{"roles": [], "callers": ["*"], "llm": "granite", "prompt": "", "tools": []}',
           1, 'jasmine', NULL, NULL, NULL, NULL, NULL, NULL),
          ('idle', 'hr', 'Idle', '{"roles": [], "callers": ["*"], "llm": "", "prompt": ""}',
           1, 'jasmine', NULL, NULL, NULL, NULL, NULL, NULL);
        INSERT INTO airlock_code (airlock_id, seq, bytes) VALUES ('deployed', 0, '\\x504b');
        """);

    // Act
    migrate(url, "3");

    // Assert
    Map<String, JsonNode> airlocks = definitions(url, "airlocks", "id");
    assertThat(airlocks.get("deployed"))
        .isEqualTo(
            JSON.readTree(
                """
                {"roles": ["employee"], "callers": ["*"], "clients": [],
                 "llms": [{"id": "granite", "provider": "ollama", "url": "http://o", "model": "g",
                           "secret_ref": ""}],
                 "data_sources": [{"id": "acme_hr", "url": "jdbc:x", "secret_ref": "hr-deployed-ds-1"}]}\
                """));
    assertThat(airlocks.get("idle"))
        .isEqualTo(JSON.readTree("{\"roles\": [], \"callers\": [\"*\"]}"));
    Map<String, JsonNode> versions =
        definitions(url, "airlock_versions", "airlock_id || '/' || version");
    assertThat(versions.keySet()).containsExactly("deployed/v1", "named/v1");
    assertThat(versions.get("deployed/v1"))
        .isEqualTo(
            JSON.readTree(
                """
                {"context_preparators": ["Handbook"], "memory_handlers": [], "llm": "granite",
                 "prompt": "p", "tools": ["read_records"], "verifiers": []}\
                """));
    assertThat(
            query(
                url,
                "SELECT airlock_id, version, jar_digest, jar_language, created_by, revision"
                    + " FROM airlock_versions WHERE airlock_id = 'deployed'"))
        .isEqualTo("deployed|v1|sha256:ab|java|jasmine|1");
    assertThat(query(url, "SELECT airlock_id, version, seq FROM airlock_version_code"))
        .isEqualTo("deployed|v1|0");
    assertThat(
            query(
                url,
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'airlocks'"
                    + " AND column_name LIKE 'jar_%'"))
        .isEqualTo("0");
    assertThat(
            query(
                url,
                "SELECT is_nullable FROM information_schema.columns WHERE table_name ="
                    + " 'audit_events' AND column_name = 'version'"))
        .isEqualTo("YES");
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

  /** The first row of the query, its columns joined by {@code |}. */
  private static String query(String url, String sql) throws SQLException {
    try (Connection db = DriverManager.getConnection(url, "aisolate", "aisolate");
        Statement statement = db.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      StringBuilder joined = new StringBuilder();
      for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
        joined.append(i == 1 ? "" : "|").append(rows.getString(i));
      }
      return joined.toString();
    }
  }

  private static Map<String, JsonNode> definitions(String url, String table, String key)
      throws SQLException {
    Map<String, JsonNode> definitions = new LinkedHashMap<>();
    try (Connection db = DriverManager.getConnection(url, "aisolate", "aisolate");
        Statement statement = db.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT " + key + ", definition::text FROM " + table + " ORDER BY 1")) {
      while (rows.next()) {
        definitions.put(rows.getString(1), JSON.readTree(rows.getString(2)));
      }
    }
    return definitions;
  }
}
