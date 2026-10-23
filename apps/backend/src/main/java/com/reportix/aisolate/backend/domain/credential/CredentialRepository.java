package com.reportix.aisolate.backend.domain.credential;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code credentials} and {@code credential_events} tables. Events are written in a transaction
 * of their own that first became {@code aisolate_audit_writer}: it demotes only itself, and the
 * line stays when the caller's transaction rolls back.
 */
@Repository
public class CredentialRepository {

  private static final String COLUMNS =
      "secret_ref, owner_kind, owner, kind, item_id, key_id, nonce, ciphertext, created_by";

  private final JdbcClient db;
  private final TransactionTemplate requiresNew;

  public CredentialRepository(JdbcClient db, PlatformTransactionManager tm) {
    this.db = db;
    this.requiresNew = new TransactionTemplate(tm);
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public record Row(
      String secretRef,
      String ownerKind,
      String owner,
      String kind,
      String itemId,
      String keyId,
      byte[] nonce,
      byte[] ciphertext,
      String createdBy) {}

  public record Event(
      String actor,
      String action,
      String secretRef,
      String ownerKind,
      String owner,
      String kind,
      String itemId,
      String reason) {

    public static Event of(Row row, String actor, String action, String reason) {
      return new Event(
          actor,
          action,
          row.secretRef(),
          row.ownerKind(),
          row.owner(),
          row.kind(),
          row.itemId(),
          reason);
    }
  }

  /** In the caller's transaction. */
  public void insert(Row r) {
    db.sql("INSERT INTO credentials (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            r.secretRef(),
            r.ownerKind(),
            r.owner(),
            r.kind(),
            r.itemId(),
            r.keyId(),
            r.nonce(),
            r.ciphertext(),
            r.createdBy())
        .update();
  }

  public Optional<Row> find(String secretRef) {
    return db.sql("SELECT " + COLUMNS + " FROM credentials WHERE secret_ref = ?")
        .param(secretRef)
        .query(CredentialRepository::readRow)
        .optional();
  }

  public void record(Event e) {
    requiresNew.executeWithoutResult(s -> insertEvent(e));
  }

  /** Demotes the transaction it runs in, so nothing but the event may follow it. */
  private void insertEvent(Event e) {
    db.sql("SET LOCAL ROLE aisolate_audit_writer").update();
    db.sql(
            "INSERT INTO credential_events (actor, action, secret_ref, owner_kind, owner,"
                + " kind, item_id, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            e.actor(),
            e.action(),
            e.secretRef(),
            e.ownerKind(),
            e.owner(),
            e.kind(),
            e.itemId(),
            e.reason())
        .update();
  }

  /**
   * Own transaction (it runs from an after-commit listener, where a plain statement never commits);
   * the delete and its event commit together.
   */
  public Optional<Row> deleteOrphan(String secretRef, String actor) {
    return requiresNew.execute(
        s -> {
          Optional<Row> row =
              db.sql("DELETE FROM credentials WHERE secret_ref = ? RETURNING " + COLUMNS)
                  .param(secretRef)
                  .query(CredentialRepository::readRow)
                  .optional();
          row.ifPresent(r -> insertEvent(Event.of(r, actor, "deleted", null)));
          return row;
        });
  }

  /** In the caller's transaction; the install's credentials stay. */
  public List<Row> deleteSpacesAndAirlocks() {
    return db.sql(
            "DELETE FROM credentials WHERE owner_kind IN ('space', 'airlock') RETURNING " + COLUMNS)
        .query(CredentialRepository::readRow)
        .list();
  }

  public List<Event> events(String secretRef) {
    return db.sql(
            "SELECT actor, action, secret_ref, owner_kind, owner, kind, item_id, reason"
                + " FROM credential_events WHERE secret_ref = ? ORDER BY id")
        .param(secretRef)
        .query(
            (rs, n) ->
                new Event(
                    rs.getString("actor"),
                    rs.getString("action"),
                    rs.getString("secret_ref"),
                    rs.getString("owner_kind"),
                    rs.getString("owner"),
                    rs.getString("kind"),
                    rs.getString("item_id"),
                    rs.getString("reason")))
        .list();
  }

  private static Row readRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Row(
        rs.getString("secret_ref"),
        rs.getString("owner_kind"),
        rs.getString("owner"),
        rs.getString("kind"),
        rs.getString("item_id"),
        rs.getString("key_id"),
        rs.getBytes("nonce"),
        rs.getBytes("ciphertext"),
        rs.getString("created_by"));
  }
}
