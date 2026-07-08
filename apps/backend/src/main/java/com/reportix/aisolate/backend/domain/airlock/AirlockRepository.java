package com.reportix.aisolate.backend.domain.airlock;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.identity.IdentityProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code airlocks} table: one row per airlock, its identity in columns, the rest of its form as
 * the definition JSON, saved in place ({@code revision} counts the saves). Its code is the jar the
 * last deploy brought: the bytes stay in {@code airlock_code} in chunks, read only when a pod
 * fetches them; what the jar holds is read with the row.
 */
@Repository
@RequiredArgsConstructor
public class AirlockRepository {

  /** The code a deploy brings: the jar on disk and what it holds. */
  public record Code(Path file, JarInspector.Contents contents) {}

  /** Postgres sends a bytea whole: chunks keep each row, and what reads one, small. */
  static final int CHUNK = 1024 * 1024;

  private static final String SELECT =
      "SELECT a.id, a.space_id, a.name, a.description, a.definition::text AS definition,"
          + " a.revision, a.created_by, a.created_by_name, a.created_at, a.updated_at,"
          + " a.jar_digest, a.jar_size, a.jar_tools::text AS jar_tools, a.jar_handlers::text AS"
          + " jar_handlers, a.jar_rules::text AS jar_rules, a.jar_language FROM airlocks a";

  private static final TypeReference<List<JarInspector.Tool>> TOOLS = new TypeReference<>() {};
  private static final TypeReference<List<JarInspector.Handler>> HANDLERS =
      new TypeReference<>() {};
  private static final TypeReference<List<JarInspector.RuleSet>> RULES = new TypeReference<>() {};

  private final JdbcClient db;
  private final JsonMapper json;
  private final IdentityProperties properties;

  public Optional<Airlock> find(String id) {
    return db.sql(SELECT + " WHERE a.id = ?").param(id).query(this::readAirlock).optional();
  }

  /**
   * An airlock addressed through its space.
   *
   * @throws AirlockNotFoundException no such airlock, or it lives in another space
   */
  public Airlock require(String spaceId, String id) {
    return find(id)
        .filter(airlock -> airlock.spaceId().equals(spaceId))
        .orElseThrow(() -> new AirlockNotFoundException(id));
  }

  /**
   * {@link #require}, the row locked until the caller's transaction ends: a save reads what it
   * replaces under the lock, so two saves never both start from one stored row.
   */
  public Airlock lock(String spaceId, String id) {
    return db.sql(SELECT + " WHERE a.id = ? FOR NO KEY UPDATE OF a")
        .param(id)
        .query(this::readAirlock)
        .optional()
        .filter(airlock -> airlock.spaceId().equals(spaceId))
        .orElseThrow(() -> new AirlockNotFoundException(id));
  }

  /** The airlock whose cluster objects are named {@code <space>-<airlock>-…} with this prefix. */
  public Optional<Airlock> findByClusterName(String name) {
    return db.sql(SELECT + " WHERE a.space_id || '-' || a.id = ? LIMIT 1")
        .param(name)
        .query(this::readAirlock)
        .optional();
  }

  /** Every airlock in the space, by id. */
  public List<Airlock> list(String spaceId) {
    return db.sql(SELECT + " WHERE a.space_id = ? ORDER BY a.id")
        .param(spaceId)
        .query(this::readAirlock)
        .list();
  }

  /**
   * A new airlock at revision 1, with its code when a deploy brings one.
   *
   * @param code null for an airlock created in the AIsolate UI
   */
  public Airlock insert(String spaceId, AirlockFormDTO form, Code code, Creator creator) {
    db.sql(
            "INSERT INTO airlocks (id, space_id, name, description, definition, created_by,"
                + " created_by_name) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)")
        .params(
            form.id(),
            spaceId,
            form.name(),
            form.description(),
            toDefinition(form),
            creator.username(),
            creator.name())
        .update();
    if (code != null) {
      storeCode(form.id(), code);
    }
    return find(form.id()).orElseThrow();
  }

  /**
   * Saves the airlock in place; the revision counts the save.
   *
   * @param code null keeps the code it has
   */
  public Airlock update(String id, AirlockFormDTO form, Code code) {
    int updated =
        db.sql(
                "UPDATE airlocks SET name = ?, description = ?, definition = ?::jsonb, revision ="
                    + " revision + 1, updated_at = now() WHERE id = ?")
            .params(form.name(), form.description(), toDefinition(form), id)
            .update();
    if (updated != 1) {
      throw new AirlockNotFoundException(id);
    }
    if (code != null) {
      storeCode(id, code);
    }
    return find(id).orElseThrow();
  }

  /** The Secret names a save wrote; not a save of its own, so the revision stays. */
  public Airlock updateDataSources(String id, List<DataSourceFormDTO> rows) {
    db.sql(
            "UPDATE airlocks SET definition = jsonb_set(definition, '{data_sources}', ?::jsonb)"
                + " WHERE id = ?")
        .params(json.writeValueAsString(rows), id)
        .update();
    return find(id).orElseThrow();
  }

  /** The Secret names of the models' keys a save wrote; not a save of its own. */
  public Airlock updateLlms(String id, List<LlmFormDTO> rows) {
    db.sql(
            "UPDATE airlocks SET definition = jsonb_set(definition, '{llms}', ?::jsonb) WHERE id ="
                + " ?")
        .params(json.writeValueAsString(rows), id)
        .update();
    return find(id).orElseThrow();
  }

  /**
   * The airlock's code bytes into {@code out}, a chunk at a time, each its own query: a slow pod
   * holds no connection. A deploy in between mixes two codes; the pod's digest check refuses that.
   */
  public void streamCode(String airlockId, OutputStream out) throws IOException {
    for (int seq = 0; ; seq++) {
      Optional<byte[]> chunk =
          db.sql("SELECT bytes FROM airlock_code WHERE airlock_id = ? AND seq = ?")
              .params(airlockId, seq)
              .query(byte[].class)
              .optional();
      if (chunk.isEmpty()) {
        return;
      }
      out.write(chunk.get());
    }
  }

  /** A global data source or model, or a space's or an airlock's, still names the Secret. */
  public boolean isSecretReferenced(String name) {
    String probe = json.writeValueAsString(List.of(Map.of("secret_ref", name)));
    return db.sql(
            "SELECT EXISTS (SELECT 1 FROM data_sources WHERE secret_ref = ?"
                + " UNION ALL SELECT 1 FROM llms WHERE secret_ref = ?"
                + " UNION ALL SELECT 1 FROM spaces WHERE definition->'data_sources' @> ?::jsonb"
                + " OR definition->'llms' @> ?::jsonb"
                + " UNION ALL SELECT 1 FROM airlocks WHERE definition->'data_sources' @> ?::jsonb"
                + " OR definition->'llms' @> ?::jsonb)")
        .params(name, name, probe, probe, probe, probe)
        .query(Boolean.class)
        .single();
  }

  /** The code goes with the rows: {@code airlock_code} cascades. */
  public int deleteAll() {
    return db.sql("DELETE FROM airlocks").update();
  }

  /** In the caller's transaction: the old chunks go with the save. */
  private void storeCode(String airlockId, Code code) {
    JarInspector.Contents contents = code.contents();
    db.sql("DELETE FROM airlock_code WHERE airlock_id = ?").param(airlockId).update();
    try (InputStream in = Files.newInputStream(code.file())) {
      for (int seq = 0; ; seq++) {
        byte[] chunk = in.readNBytes(CHUNK);
        if (chunk.length == 0) {
          break;
        }
        db.sql("INSERT INTO airlock_code (airlock_id, seq, bytes) VALUES (?, ?, ?)")
            .params(airlockId, seq, chunk)
            .update();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    db.sql(
            "UPDATE airlocks SET jar_digest = ?, jar_size = ?, jar_tools = ?::jsonb, jar_handlers ="
                + " ?::jsonb, jar_rules = ?::jsonb, jar_language = ? WHERE id = ?")
        .params(
            contents.digest(),
            contents.size(),
            json.writeValueAsString(contents.tools()),
            json.writeValueAsString(contents.handlers()),
            json.writeValueAsString(contents.ruleSets()),
            contents.language(),
            airlockId)
        .update();
  }

  /** The form without the fields that have their own columns, and without credentials. */
  private String toDefinition(AirlockFormDTO form) {
    ObjectNode definition = json.valueToTree(form);
    definition.remove(List.of("id", "name", "description"));
    return json.writeValueAsString(definition);
  }

  private Airlock readAirlock(ResultSet rs, int i) throws SQLException {
    String id = rs.getString("id");
    String spaceId = rs.getString("space_id");
    ObjectNode definition = (ObjectNode) json.readTree(rs.getString("definition"));
    definition.put("id", id);
    definition.put("name", rs.getString("name"));
    definition.put("description", rs.getString("description"));
    AirlockFormDTO form = json.treeToValue(definition, AirlockFormDTO.class);
    String digest = rs.getString("jar_digest");
    return new Airlock(
        id,
        spaceId,
        rs.getInt("revision"),
        properties.chatEndpoint(spaceId, id),
        rs.getString("created_by"),
        rs.getString("created_by_name"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class),
        form,
        null,
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
