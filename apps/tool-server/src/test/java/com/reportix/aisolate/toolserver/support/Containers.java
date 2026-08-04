package com.reportix.aisolate.toolserver.support;

import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.toolserver.domain.sql.CompiledQuery;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The customer's database, seeded the same way as docker/compose; Keycloak is shared and AIsolate's
 * database is never started here.
 */
public final class Containers {

  private static PostgreSQLContainer acme;

  /** The customer's database, seeded from the compose SQL files. */
  public static synchronized PostgreSQLContainer acme() {
    if (acme == null) {
      acme =
          // pgvector, like compose: the documents tables carry the context preparators' vectors
          new PostgreSQLContainer(
                  DockerImageName.parse("pgvector/pgvector:pg18")
                      .asCompatibleSubstituteFor("postgres"))
              .withDatabaseName("acme")
              .withUsername("acme")
              .withPassword("acme");
      acme.withCopyFileToContainer(
          MountableFile.forHostPath(Keycloak.DOCKER.resolve("postgres/acme")),
          "/docker-entrypoint-initdb.d");
      acme.start();
    }
    return acme;
  }

  /** A connection to {@link #acme()} as its owner, bypassing the pools and their read-only role. */
  public static Connection connect() throws SQLException {
    PostgreSQLContainer db = acme();
    return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
  }

  /** The compiled statement run on {@link #acme()}, its rows by column label. */
  public static List<Map<String, Object>> rows(CompiledQuery query) throws SQLException {
    try (Connection connection = connect();
        PreparedStatement statement = connection.prepareStatement(query.sql())) {
      for (int i = 0; i < query.params().size(); i++) {
        statement.setObject(i + 1, query.params().get(i));
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
            row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
          }
          rows.add(row);
        }
      }
      return rows;
    }
  }

  private Containers() {}
}
