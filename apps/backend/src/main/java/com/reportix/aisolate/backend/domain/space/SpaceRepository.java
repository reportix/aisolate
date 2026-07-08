package com.reportix.aisolate.backend.domain.space;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code spaces} table: identity in columns, members, LLMs and data sources as definition JSON.
 */
@Repository
@RequiredArgsConstructor
public class SpaceRepository {

  private static final String COLUMNS =
      "id, name, description, definition::text AS definition, created_by, created_by_name,"
          + " created_at, updated_at";

  /** The advisory lock every create of a space or an airlock takes. */
  public static final long NAMES_LOCK = 0x4149534F4C415445L;

  private final JdbcClient db;
  private final JsonMapper json;

  /**
   * Holds every other create until the caller's transaction ends: an id is checked against the
   * cluster names of the other levels, which no constraint covers, so two creates must not both
   * pass the check.
   */
  // Limit: one install-wide lock for every create; creates are rare and short. Per-space keys if
  // they ever contend.
  public void lockNames() {
    db.sql("SELECT pg_advisory_xact_lock(?)").param(NAMES_LOCK).query().listOfRows();
  }

  public List<Space> list() {
    return db.sql("SELECT " + COLUMNS + " FROM spaces ORDER BY id").query(this::readSpace).list();
  }

  public Optional<Space> find(String id) {
    return db.sql("SELECT " + COLUMNS + " FROM spaces WHERE id = ?")
        .param(id)
        .query(this::readSpace)
        .optional();
  }

  /**
   * @throws SpaceNotFoundException no space has this id
   */
  public Space require(String id) {
    return find(id).orElseThrow(() -> new SpaceNotFoundException(id));
  }

  /** {@link #require}, the row locked until the caller's transaction ends. */
  public Space lock(String id) {
    return db.sql("SELECT " + COLUMNS + " FROM spaces WHERE id = ? FOR NO KEY UPDATE")
        .param(id)
        .query(this::readSpace)
        .optional()
        .orElseThrow(() -> new SpaceNotFoundException(id));
  }

  public Space insert(SpaceFormDTO form, Creator creator) {
    db.sql(
            "INSERT INTO spaces (id, name, description, definition, created_by, created_by_name)"
                + " VALUES (?, ?, ?, ?::jsonb, ?, ?)")
        .params(
            form.id(),
            form.name(),
            form.description(),
            toDefinition(form),
            creator.username(),
            creator.name())
        .update();
    return require(form.id());
  }

  public Space update(String id, SpaceFormDTO form) {
    int updated =
        db.sql(
                "UPDATE spaces SET name = ?, description = ?, definition = ?::jsonb, updated_at ="
                    + " now() WHERE id = ?")
            .params(form.name(), form.description(), toDefinition(form), id)
            .update();
    if (updated != 1) {
      throw new SpaceNotFoundException(id);
    }
    return require(id);
  }

  /** The Secret names a save wrote; not an edit of its own. */
  public Space updateDataSources(String id, List<DataSourceFormDTO> rows) {
    db.sql(
            "UPDATE spaces SET definition = jsonb_set(definition, '{data_sources}', ?::jsonb)"
                + " WHERE id = ?")
        .params(json.writeValueAsString(rows), id)
        .update();
    return require(id);
  }

  /** The Secret names of the models' keys a save wrote; not an edit of its own. */
  public Space updateLlms(String id, List<LlmFormDTO> rows) {
    db.sql("UPDATE spaces SET definition = jsonb_set(definition, '{llms}', ?::jsonb) WHERE id = ?")
        .params(json.writeValueAsString(rows), id)
        .update();
    return require(id);
  }

  public int deleteAll() {
    return db.sql("DELETE FROM spaces").update();
  }

  /** The form without the fields that have their own columns, and without credentials. */
  private String toDefinition(SpaceFormDTO form) {
    ObjectNode definition = json.valueToTree(form);
    definition.remove(List.of("id", "name", "description"));
    return json.writeValueAsString(definition);
  }

  private Space readSpace(ResultSet rs, int i) throws SQLException {
    SpaceFormDTO definition = json.readValue(rs.getString("definition"), SpaceFormDTO.class);
    return new Space(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("description"),
        definition.members(),
        definition.llms(),
        definition.dataSources(),
        rs.getString("created_by"),
        rs.getString("created_by_name"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class),
        null);
  }
}
