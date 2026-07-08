package com.reportix.aisolate.backend.domain.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code audit_events} table. Every statement but the retention's runs inside a transaction
 * that first became {@code aisolate_audit_writer} ({@link #becomeWriter}): INSERT and SELECT, so
 * this code path cannot change or remove a line even by mistake.
 */
@Repository
@RequiredArgsConstructor
public class AuditRepository {

  /** The class half of the chain locks: apart from every other advisory lock of the backend. */
  private static final int CHAIN_LOCK = 0x41554454; // "AUDT"

  private static final String COLUMNS =
      "id, received_at, ts, space, airlock, revision, event, trace_id, user_email, client,"
          + " outcome, reason, question, answer, figures_sourced, figures_computed,"
          + " figures_unsourced, figures_mismatch, input_tokens, output_tokens, duration_ms";

  private final JdbcClient db;

  /** A stored line: its columns, its link in the chain, its body when asked for. */
  public record Row(
      long id,
      Instant receivedAt,
      AuditLine.Columns columns,
      String prevHash,
      String hash,
      String body) {}

  /**
   * @param spaces the only spaces to list, a developer's; null for every space
   */
  public record Filter(
      String space,
      List<String> spaces,
      String airlock,
      String user,
      String outcome,
      String traceId,
      String question,
      String search,
      Instant from,
      Instant to) {}

  /** What the Audit page's one search box looks in. */
  private static final List<String> SEARCHED =
      List.of("space", "airlock", "user_email", "client", "trace_id", "question");

  /** Until the transaction ends. */
  public void becomeWriter() {
    db.sql("SET LOCAL ROLE aisolate_audit_writer").update();
  }

  /** One airlock's lines are chained one at a time, across replicas. */
  public void lockChain(String space, String airlock) {
    db.sql("SELECT pg_advisory_xact_lock(?, ?)")
        .params(CHAIN_LOCK, (space + "/" + airlock).hashCode())
        .query()
        .listOfRows();
  }

  public Optional<Long> findRepeat(String space, String airlock, String digest) {
    return db.sql(
            "SELECT id FROM audit_events WHERE digest = ? AND space = ? AND airlock = ? LIMIT 1")
        .params(digest, space, airlock)
        .query(Long.class)
        .optional();
  }

  /** The hash of the airlock's newest line. */
  public Optional<String> head(String space, String airlock) {
    return db.sql(
            "SELECT hash FROM audit_events WHERE space = ? AND airlock = ? ORDER BY id DESC LIMIT"
                + " 1")
        .params(space, airlock)
        .query(String.class)
        .optional();
  }

  public long insert(AuditLine line, String prevHash, String hash) {
    AuditLine.Columns c = line.columns();
    return db.sql(
            "INSERT INTO audit_events (ts, space, airlock, revision, event, trace_id,"
                + " user_email, client, outcome, reason, question, answer, figures_sourced,"
                + " figures_computed, figures_unsourced, figures_mismatch, input_tokens,"
                + " output_tokens, duration_ms, body, digest, prev_hash, hash) VALUES (?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")
        .params(
            Timestamp.from(c.ts()),
            c.space(),
            c.airlock(),
            c.revision(),
            c.event(),
            c.traceId(),
            c.user(),
            c.client(),
            c.outcome(),
            c.reason(),
            c.question(),
            c.answer(),
            c.sourced(),
            c.computed(),
            c.unsourced(),
            c.mismatch(),
            c.inputTokens(),
            c.outputTokens(),
            c.durationMs(),
            line.body(),
            line.digest(),
            prevHash,
            hash)
        .query(Long.class)
        .single();
  }

  /** Newest first, below {@code before} when given; no body. */
  public List<Row> list(Filter f, Long before, int limit) {
    StringBuilder where = new StringBuilder(" WHERE true");
    List<Object> params = new ArrayList<>();
    add(where, params, "space = ?", f.space());
    add(
        where,
        params,
        "space = ANY(?)",
        f.spaces() == null ? null : f.spaces().toArray(String[]::new));
    add(where, params, "airlock = ?", f.airlock());
    add(where, params, "lower(user_email) = lower(?)", f.user());
    add(where, params, "outcome = ?", f.outcome());
    add(where, params, "trace_id = ?", f.traceId());
    // strpos, not LIKE: a % or _ typed in the search is a character, not a wildcard.
    add(where, params, "strpos(lower(question), lower(?)) > 0", f.question());
    if (f.search() != null) {
      where.append(
          SEARCHED.stream()
              .map(c -> "strpos(lower(coalesce(" + c + ", '')), lower(?)) > 0")
              .collect(Collectors.joining(" OR ", " AND (", ")")));
      SEARCHED.forEach(c -> params.add(f.search()));
    }
    add(where, params, "ts >= ?", f.from() == null ? null : Timestamp.from(f.from()));
    add(where, params, "ts < ?", f.to() == null ? null : Timestamp.from(f.to()));
    add(where, params, "id < ?", before);
    params.add(limit);
    return db.sql(
            "SELECT "
                + COLUMNS
                + ", prev_hash, hash, NULL AS body FROM audit_events"
                + where
                + " ORDER BY id DESC LIMIT ?")
        .params(params)
        .query(AuditRepository::readRow)
        .list();
  }

  /** The newest exchange of this airlock that called a tool, with its body. */
  public Optional<Row> latestToolExchange(String space, String airlock) {
    return db.sql(
            "SELECT "
                + COLUMNS
                + ", prev_hash, hash, body FROM audit_events WHERE space = ? AND airlock = ? AND"
                + " event = 'exchange' AND body::jsonb -> 'tools' <> '[]'::jsonb"
                + " ORDER BY id DESC LIMIT 1")
        .params(space, airlock)
        .query(AuditRepository::readRow)
        .optional();
  }

  public Optional<Row> find(long id) {
    return db.sql("SELECT " + COLUMNS + ", prev_hash, hash, body FROM audit_events WHERE id = ?")
        .param(id)
        .query(AuditRepository::readRow)
        .optional();
  }

  /** The bodies of these lines, by id. */
  public Map<Long, String> bodies(List<Long> ids) {
    Map<Long, String> bodies = new HashMap<>();
    db.sql("SELECT id, body FROM audit_events WHERE id = ANY(?)")
        .param(ids.toArray(Long[]::new))
        .query((RowCallbackHandler) rs -> bodies.put(rs.getLong("id"), rs.getString("body")));
    return bodies;
  }

  /** The airlock's chain from {@code after} on, oldest first, a page of it. */
  public List<Row> chain(String space, String airlock, long after, int limit) {
    return db.sql(
            "SELECT "
                + COLUMNS
                + ", prev_hash, hash, body FROM audit_events WHERE space = ? AND airlock = ?"
                + " AND id > ? ORDER BY id LIMIT ?")
        .params(space, airlock, after, limit)
        .query(AuditRepository::readRow)
        .list();
  }

  public record Extent(long events, Long firstId, Long lastId) {}

  public Extent extent(String space, String airlock) {
    return db.sql(
            "SELECT count(*), min(id), max(id) FROM audit_events WHERE space = ? AND airlock = ?")
        .params(space, airlock)
        .query(
            (rs, n) ->
                new Extent(rs.getLong(1), rs.getObject(2, Long.class), rs.getObject(3, Long.class)))
        .single();
  }

  // Retention, as the owner: the writer may not create or drop a partition.

  public void ensurePartition(Instant day) {
    db.sql("SELECT audit_events_partition((? AT TIME ZONE 'UTC')::date)")
        .param(Timestamp.from(day))
        .query()
        .listOfRows();
  }

  public int dropBefore(Instant keepFrom) {
    return db.sql("SELECT audit_events_drop_before((? AT TIME ZONE 'UTC')::date)")
        .param(Timestamp.from(keepFrom))
        .query(Integer.class)
        .single();
  }

  private static void add(StringBuilder where, List<Object> params, String clause, Object value) {
    if (value != null) {
      where.append(" AND ").append(clause);
      params.add(value);
    }
  }

  private static Row readRow(ResultSet rs, int n) throws SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
        new AuditLine.Columns(
            rs.getObject("ts", OffsetDateTime.class).toInstant(),
            rs.getString("space"),
            rs.getString("airlock"),
            rs.getString("revision"),
            rs.getString("event"),
            rs.getString("trace_id"),
            rs.getString("user_email"),
            rs.getString("client"),
            rs.getString("outcome"),
            rs.getString("reason"),
            rs.getString("question"),
            rs.getString("answer"),
            rs.getObject("figures_sourced", Integer.class),
            rs.getObject("figures_computed", Integer.class),
            rs.getObject("figures_unsourced", Integer.class),
            rs.getObject("figures_mismatch", Integer.class),
            rs.getObject("input_tokens", Integer.class),
            rs.getObject("output_tokens", Integer.class),
            rs.getObject("duration_ms", Long.class)),
        rs.getString("prev_hash"),
        rs.getString("hash"),
        rs.getString("body"));
  }
}
