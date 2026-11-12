package com.reportix.aisolate.backend.domain.airlock;

import com.reportix.aisolate.backend.domain.Creator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code airlocks} table: one row per airlock, its identity in columns, the rest of its form as
 * the definition JSON, saved in place ({@code revision} counts the saves); the versions hold what
 * runs and the code.
 */
@Repository
@RequiredArgsConstructor
public class AirlockRepository {

  private static final String SELECT =
      "SELECT a.id, a.space_id, a.name, a.description, a.definition::text AS definition,"
          + " a.revision, (SELECT count(*) FROM airlock_versions v WHERE v.airlock_id = a.id) AS"
          + " versions, a.created_by, a.created_by_name, a.created_at, a.updated_at FROM"
          + " airlocks a";

  private final JdbcClient db;
  private final JsonMapper json;

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

  /** A new airlock at revision 1, without versions. */
  public Airlock insert(String spaceId, AirlockFormDTO form, Creator creator) {
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
    return find(form.id()).orElseThrow();
  }

  /** Saves the airlock in place; the revision counts the save. */
  public Airlock update(String id, AirlockFormDTO form) {
    int updated =
        db.sql(
                "UPDATE airlocks SET name = ?, description = ?, definition = ?::jsonb, revision ="
                    + " revision + 1, updated_at = now() WHERE id = ?")
            .params(form.name(), form.description(), toDefinition(form), id)
            .update();
    if (updated != 1) {
      throw new AirlockNotFoundException(id);
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
   * A global data source or model, or a space's or an airlock's, still names the Secret. A version
   * holds no credential, so none of its rows can.
   */
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

  /** The versions and their code go with the rows: both cascade. */
  public int deleteAll() {
    return db.sql("DELETE FROM airlocks").update();
  }

  /** The form without the fields that have their own columns, and without credentials. */
  private String toDefinition(AirlockFormDTO form) {
    ObjectNode definition = json.valueToTree(form);
    definition.remove(List.of("id", "name", "description"));
    return json.writeValueAsString(definition);
  }

  private Airlock readAirlock(ResultSet rs, int i) throws SQLException {
    String id = rs.getString("id");
    ObjectNode definition = (ObjectNode) json.readTree(rs.getString("definition"));
    definition.put("id", id);
    definition.put("name", rs.getString("name"));
    definition.put("description", rs.getString("description"));
    return new Airlock(
        id,
        rs.getString("space_id"),
        rs.getInt("revision"),
        rs.getInt("versions"),
        rs.getString("created_by"),
        rs.getString("created_by_name"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class),
        json.treeToValue(definition, AirlockFormDTO.class));
  }
}
