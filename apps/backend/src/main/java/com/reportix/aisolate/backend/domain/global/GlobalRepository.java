package com.reportix.aisolate.backend.domain.global;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Metadata;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code llms} and {@code data_sources} tables: what every space inherits. */
@Repository
@RequiredArgsConstructor
public class GlobalRepository {

  private static final String AUDIT = ", created_by, created_by_name, created_at, updated_at";
  private static final String LLMS =
      "SELECT id, provider, url, model, secret_ref" + AUDIT + " FROM llms";
  private static final String DATA_SOURCES =
      "SELECT id, url, secret_ref" + AUDIT + " FROM data_sources";

  private final JdbcClient db;

  /** The install's Global document: every global LLM and data source, by id. */
  public GlobalDocument document() {
    return new GlobalDocument(
        new Metadata(GlobalDocument.NAME),
        new GlobalDocument.Spec(
            llms().stream().map(row -> row.toRow().toLlm()).toList(),
            dataSources().stream().map(row -> row.toRow().toDataSource()).toList()));
  }

  public List<GlobalLlm> llms() {
    return db.sql(LLMS + " ORDER BY id").query(this::readLlm).list();
  }

  /** {@code lock}: the row locked until the caller's transaction ends. */
  public Optional<GlobalLlm> findLlm(String id, boolean lock) {
    return db.sql(LLMS + " WHERE id = ?" + (lock ? " FOR UPDATE" : ""))
        .param(id)
        .query(this::readLlm)
        .optional();
  }

  public void insertLlm(LlmFormDTO llm, Creator creator) {
    db.sql(
            "INSERT INTO llms (id, provider, url, model, secret_ref, created_by, created_by_name)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)")
        .params(
            llm.id(),
            llm.provider(),
            llm.url(),
            llm.model(),
            orNull(llm.secretRef()),
            creator.username(),
            creator.name())
        .update();
  }

  public void updateLlm(LlmFormDTO llm) {
    db.sql(
            "UPDATE llms SET provider = ?, url = ?, model = ?, secret_ref = ?, updated_at = now()"
                + " WHERE id = ?")
        .params(llm.provider(), llm.url(), llm.model(), orNull(llm.secretRef()), llm.id())
        .update();
  }

  /** The Secret name a save wrote; not an edit of its own. */
  public void updateLlmSecretRef(String id, String secretRef) {
    db.sql("UPDATE llms SET secret_ref = ? WHERE id = ?").params(secretRef, id).update();
  }

  public void deleteLlm(String id) {
    db.sql("DELETE FROM llms WHERE id = ?").param(id).update();
  }

  public List<GlobalDataSource> dataSources() {
    return db.sql(DATA_SOURCES + " ORDER BY id").query(this::readDataSource).list();
  }

  /** {@code lock}: the row locked until the caller's transaction ends. */
  public Optional<GlobalDataSource> findDataSource(String id, boolean lock) {
    return db.sql(DATA_SOURCES + " WHERE id = ?" + (lock ? " FOR UPDATE" : ""))
        .param(id)
        .query(this::readDataSource)
        .optional();
  }

  public void insertDataSource(DataSourceFormDTO row, Creator creator) {
    db.sql(
            "INSERT INTO data_sources (id, url, secret_ref, created_by, created_by_name)"
                + " VALUES (?, ?, ?, ?, ?)")
        .params(row.id(), row.url(), orNull(row.secretRef()), creator.username(), creator.name())
        .update();
  }

  public void updateDataSource(DataSourceFormDTO row) {
    db.sql("UPDATE data_sources SET url = ?, secret_ref = ?, updated_at = now() WHERE id = ?")
        .params(row.url(), orNull(row.secretRef()), row.id())
        .update();
  }

  /** The Secret name a save wrote; not an edit of its own. */
  public void updateSecretRef(String id, String secretRef) {
    db.sql("UPDATE data_sources SET secret_ref = ? WHERE id = ?").params(secretRef, id).update();
  }

  public void deleteDataSource(String id) {
    db.sql("DELETE FROM data_sources WHERE id = ?").param(id).update();
  }

  private static String orNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private GlobalLlm readLlm(ResultSet rs, int i) throws SQLException {
    return new GlobalLlm(
        rs.getString("id"),
        rs.getString("provider"),
        rs.getString("url"),
        rs.getString("model"),
        rs.getString("secret_ref"),
        rs.getString("created_by"),
        rs.getString("created_by_name"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }

  private GlobalDataSource readDataSource(ResultSet rs, int i) throws SQLException {
    return new GlobalDataSource(
        rs.getString("id"),
        rs.getString("url"),
        rs.getString("secret_ref"),
        rs.getString("created_by"),
        rs.getString("created_by_name"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class));
  }
}
