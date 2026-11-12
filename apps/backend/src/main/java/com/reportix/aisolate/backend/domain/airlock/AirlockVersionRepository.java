package com.reportix.aisolate.backend.domain.airlock;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.config.AirlockVersionDocument;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code airlock_versions} table, each row read with its airlock's space. A version is saved in
 * place; {@code revision} counts its saves. Its code is the jar the last deploy brought: the bytes
 * stay in {@code airlock_version_code} in chunks, read only when a pod fetches them; what the jar
 * holds is read with the row.
 */
@Repository
@RequiredArgsConstructor
public class AirlockVersionRepository {

  /**
   * One stored version, with the space of its airlock.
   *
   * @param jar what its code holds; null before the first deploy
   */
  public record Row(
      String airlockId,
      String spaceId,
      String version,
      String description,
      int revision,
      String createdBy,
      String createdByName,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      JsonNode definition,
      JarInspector.Contents jar) {

    /** Its resource's name, unique across the install. */
    public String name() {
      return airlockId + "-" + version;
    }

    /** How messages and logs name it: {@code hr-contracts/v1}. */
    public String label() {
      return airlockId + "/" + version;
    }
  }

  /** The code a deploy brings: the jar on disk and what it holds. */
  public record Code(Path file, JarInspector.Contents contents) {}

  /** Postgres sends a bytea whole: chunks keep each row, and what reads one, small. */
  static final int CHUNK = 1024 * 1024;

  private static final String SELECT =
      "SELECT v.airlock_id, a.space_id, v.version, v.description, v.revision, v.created_by,"
          + " v.created_by_name, v.created_at, v.updated_at, v.definition::text AS definition,"
          + " v.jar_digest, v.jar_size, v.jar_tools::text AS jar_tools, v.jar_handlers::text AS"
          + " jar_handlers, v.jar_rules::text AS jar_rules, v.jar_language FROM airlock_versions v"
          + " JOIN airlocks a ON a.id = v.airlock_id";

  private static final TypeReference<List<JarInspector.Tool>> TOOLS = new TypeReference<>() {};
  private static final TypeReference<List<JarInspector.Handler>> HANDLERS =
      new TypeReference<>() {};
  private static final TypeReference<List<JarInspector.RuleSet>> RULES = new TypeReference<>() {};

  private final JdbcClient db;
  private final JsonMapper json;

  /** The versions of one airlock, by name, digit runs as numbers. */
  public List<Row> list(String airlockId) {
    return db
        .sql(SELECT + " WHERE v.airlock_id = ?")
        .param(airlockId)
        .query(this::readRow)
        .list()
        .stream()
        .sorted(Comparator.comparing(Row::version, AirlockVersionDocument.VERSION_ORDER))
        .toList();
  }

  public Optional<Row> find(String airlockId, String version) {
    return db.sql(SELECT + " WHERE v.airlock_id = ? AND v.version = ?")
        .params(airlockId, version)
        .query(this::readRow)
        .optional();
  }

  /** {@link #find}, the row locked until the caller's transaction ends. */
  public Optional<Row> lock(String airlockId, String version) {
    return db.sql(SELECT + " WHERE v.airlock_id = ? AND v.version = ? FOR NO KEY UPDATE OF v")
        .params(airlockId, version)
        .query(this::readRow)
        .optional();
  }

  /** Every version of the space's airlocks, by airlock and name: what the cluster sync applies. */
  public List<Row> listInSpace(String spaceId) {
    return db.sql(SELECT + " WHERE a.space_id = ? ORDER BY v.airlock_id, v.version")
        .param(spaceId)
        .query(this::readRow)
        .list();
  }

  /**
   * The version of an airlock other than {@code airlockId} already named {@code name} in full: both
   * slugs allow dashes, so {@code hr-assistant}/{@code eu-v1} and {@code hr-assistant-eu}/{@code
   * v1} are one resource name. Any space: the names share a namespace.
   */
  public Optional<Row> findByFullName(String name, String airlockId) {
    return db.sql(
            SELECT + " WHERE v.airlock_id || '-' || v.version = ? AND v.airlock_id <> ? LIMIT 1")
        .params(name, airlockId)
        .query(this::readRow)
        .optional();
  }

  /** The version whose cluster objects are named {@code <space>-<airlock>-<version>-…}. */
  public Optional<Row> findByClusterName(String name) {
    return db.sql(
            SELECT + " WHERE a.space_id || '-' || v.airlock_id || '-' || v.version = ? LIMIT 1")
        .param(name)
        .query(this::readRow)
        .optional();
  }

  /**
   * A new version at revision 1, with its code when a deploy brings one.
   *
   * @param code null for a version saved in the AIsolate UI
   */
  public Row insert(String airlockId, VersionFormDTO form, Code code, Creator creator) {
    db.sql(
            "INSERT INTO airlock_versions (airlock_id, version, description, definition,"
                + " created_by, created_by_name) VALUES (?, ?, ?, ?::jsonb, ?, ?)")
        .params(
            airlockId,
            form.version(),
            form.description(),
            form.toDefinitionJson(json),
            creator.username(),
            creator.name())
        .update();
    if (code != null) {
      storeCode(airlockId, form.version(), code);
    }
    return find(airlockId, form.version()).orElseThrow();
  }

  /**
   * Saves the version in place; the revision counts the save.
   *
   * @param code null keeps the code it has
   */
  public Row update(String airlockId, VersionFormDTO form, Code code) {
    int updated =
        db.sql(
                "UPDATE airlock_versions SET description = ?, definition = ?::jsonb, revision ="
                    + " revision + 1, updated_at = now() WHERE airlock_id = ? AND version = ?")
            .params(form.description(), form.toDefinitionJson(json), airlockId, form.version())
            .update();
    if (updated != 1) {
      throw new AirlockNotFoundException(airlockId + "/" + form.version(), "unknown version");
    }
    if (code != null) {
      storeCode(airlockId, form.version(), code);
    }
    return find(airlockId, form.version()).orElseThrow();
  }

  /**
   * The version's code bytes into {@code out}, a chunk at a time, each its own query: a slow pod
   * holds no connection. A deploy in between mixes two codes; the pod's digest check refuses that.
   */
  public void streamCode(String airlockId, String version, OutputStream out) throws IOException {
    for (int seq = 0; ; seq++) {
      Optional<byte[]> chunk =
          db.sql(
                  "SELECT bytes FROM airlock_version_code WHERE airlock_id = ? AND version = ? AND"
                      + " seq = ?")
              .params(airlockId, version, seq)
              .query(byte[].class)
              .optional();
      if (chunk.isEmpty()) {
        return;
      }
      out.write(chunk.get());
    }
  }

  /** The code goes with the rows: {@code airlock_version_code} cascades. */
  public int deleteAll() {
    return db.sql("DELETE FROM airlock_versions").update();
  }

  /** In the caller's transaction: the old chunks go with the save. */
  private void storeCode(String airlockId, String version, Code code) {
    JarInspector.Contents contents = code.contents();
    db.sql("DELETE FROM airlock_version_code WHERE airlock_id = ? AND version = ?")
        .params(airlockId, version)
        .update();
    try (InputStream in = Files.newInputStream(code.file())) {
      for (int seq = 0; ; seq++) {
        byte[] chunk = in.readNBytes(CHUNK);
        if (chunk.length == 0) {
          break;
        }
        db.sql(
                "INSERT INTO airlock_version_code (airlock_id, version, seq, bytes) VALUES (?, ?,"
                    + " ?, ?)")
            .params(airlockId, version, seq, chunk)
            .update();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    db.sql(
            "UPDATE airlock_versions SET jar_digest = ?, jar_size = ?, jar_tools = ?::jsonb,"
                + " jar_handlers = ?::jsonb, jar_rules = ?::jsonb, jar_language = ? WHERE"
                + " airlock_id = ? AND version = ?")
        .params(
            contents.digest(),
            contents.size(),
            json.writeValueAsString(contents.tools()),
            json.writeValueAsString(contents.handlers()),
            json.writeValueAsString(contents.ruleSets()),
            contents.language(),
            airlockId,
            version)
        .update();
  }

  private Row readRow(ResultSet rs, int i) throws SQLException {
    String digest = rs.getString("jar_digest");
    return new Row(
        rs.getString("airlock_id"),
        rs.getString("space_id"),
        rs.getString("version"),
        rs.getString("description"),
        rs.getInt("revision"),
        rs.getString("created_by"),
        rs.getString("created_by_name"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class),
        json.readTree(rs.getString("definition")),
        digest == null
            ? null
            : new JarInspector.Contents(
                digest,
                rs.getLong("jar_size"),
                json.readValue(rs.getString("jar_tools"), TOOLS),
                json.readValue(rs.getString("jar_handlers"), HANDLERS),
                json.readValue(rs.getString("jar_rules"), RULES),
                rs.getString("jar_language")));
  }
}
