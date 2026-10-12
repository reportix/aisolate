package com.aldermoor.ehr.pre_llm;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.ContextPreparator;
import com.reportix.aisolate.sdk.DataSources;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Exchange;
import com.reportix.aisolate.sdk.Passage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Retrieval over the clinical notes of the health record, the data source {@code ehr} of the
 * department's airlock: the question is embedded with the LLM {@code nomic}, the closest notes are
 * added to the exchange under one evidence id. It reads the notes of one department, the one whose
 * airlock the exchange runs on, about the patients on the asking doctor's care team there, and only
 * for a doctor: the chief's statistics never come with a patient's notes. The code is the same in
 * every department; the airlock's id decides whose notes, and the department's database role holds
 * only its own department's notes (row-level security), so cardiology never reads, or embeds, a
 * psychiatry note. One instance serves every exchange concurrently: no fields, one connection per
 * call.
 */
@Description("Read clinical notes")
@DataSources("ehr")
public final class ClinicalNotesContextPreparator implements ContextPreparator {
  @Override
  public int order() {
    return 1;
  }

  static final String CONTEXT_PREPARATOR = "clinical-notes";
  static final String READER_ROLE = "doctor";
  // At most three notes, and only close ones: every passage is prompt the model reads before it
  // answers.
  static final int TOP_K = 3;
  static final double MAX_DISTANCE = 0.35;

  // The asking doctor's patients only: the care team in this department, by the token's e-mail.
  static final String SEARCH =
      "SELECT n.title, n.written_on::text AS section, n.body FROM ehr.notes n"
          + " JOIN ehr.care_team ct ON ct.patient_id = n.patient_id AND ct.department ="
          + " n.department JOIN ehr.clinicians c ON c.id = ct.clinician_id WHERE n.department = ?"
          + " AND c.email = ? AND n.embedding IS NOT NULL AND n.embedding <=> ?::vector <= ?"
          + " ORDER BY n.embedding <=> ?::vector, n.id LIMIT ?";

  private static final String UNINDEXED =
      "SELECT id, title, body FROM ehr.notes WHERE department = ? AND embedding IS NULL";

  private static final String INDEX = "UPDATE ehr.notes SET embedding = ?::vector WHERE id = ?";

  @Override
  public void prepare(Exchange exchange, AIsolateManager aisolate) {
    if (!aisolate.principal().roles().contains(READER_ROLE)) {
      return;
    }
    String department = aisolate.airlock().airlock();
    try (Connection db = aisolate.dataSource("ehr").getConnection()) {
      embedMissingNotes(db, department, aisolate);
      String vector = toVector(aisolate.llm("nomic").embed(exchange.question()));
      List<Passage> passages = new ArrayList<>();
      try (PreparedStatement query = db.prepareStatement(SEARCH)) {
        query.setString(1, department);
        // A token without an e-mail matches no clinician: no notes.
        query.setString(2, aisolate.principal().email());
        query.setString(3, vector);
        query.setDouble(4, MAX_DISTANCE);
        query.setString(5, vector);
        query.setInt(6, TOP_K);
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            passages.add(
                new Passage(
                    rows.getString("title"), rows.getString("section"), rows.getString("body")));
          }
        }
      }
      exchange.addEvidence(CONTEXT_PREPARATOR, passages);
    } catch (IllegalArgumentException | SQLException unavailable) {
      // Fail closed for notes: no passages, the model says it does not know. An
      // IllegalArgumentException: no data source ehr, or an LlmException: no model nomic, or one
      // that cannot embed. Never a SQLException's message: it can echo the database's address.
      String cause =
          unavailable instanceof SQLException sql
              ? sql.getClass().getName() + " SQLState=" + sql.getSQLState()
              : unavailable.getMessage();
      aisolate.log().log(System.Logger.Level.WARNING, "clinical-notes unavailable: " + cause);
    }
  }

  // Embeds on the request thread so the sample runs without an indexer; a real project
  // indexes ahead of time. The whole department's: the indexer, not what the model reads.
  private static void embedMissingNotes(Connection db, String department, AIsolateManager aisolate)
      throws SQLException {
    try (PreparedStatement select = db.prepareStatement(UNINDEXED)) {
      select.setString(1, department);
      try (ResultSet rows = select.executeQuery();
          PreparedStatement update = db.prepareStatement(INDEX)) {
        while (rows.next()) {
          String text = rows.getString("title") + "\n" + rows.getString("body");
          update.setString(1, toVector(aisolate.llm("nomic").embed(text)));
          update.setLong(2, rows.getLong("id"));
          update.executeUpdate();
        }
      }
    }
  }

  static String toVector(float[] embedding) {
    StringBuilder text = new StringBuilder("[");
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) {
        text.append(',');
      }
      text.append(embedding[i]);
    }
    return text.append(']').toString();
  }
}
