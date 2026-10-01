package com.acme.hr.pre_llm;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.ContextPreparator;
import com.reportix.aisolate.sdk.DataSources;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Exchange;
import com.reportix.aisolate.sdk.Passage;
import com.reportix.aisolate.sdk.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Governed retrieval over Acme's employment contracts, read from the data source {@code hr} of
 * {@code aisolate.yaml}: the question is embedded with the LLM {@code nomic}, the closest contract
 * sections the asking user may read are added to the exchange under one evidence id, the asking
 * user's own marked as theirs. Who may read what is decided here, from the principal AIsolate hands
 * over: the CEO reads every contract, anyone else only the one that names their e-mail as owner.
 * One instance serves every exchange concurrently: no fields, one connection per call.
 */
@Description("Read employment contracts")
@DataSources("hr")
public final class ContractsContextPreparator implements ContextPreparator {
  @Override
  public int order() {
    return 1;
  }

  static final String CONTEXT_PREPARATOR = "contracts";
  // Two contracts of five short sections: up to five passages, an employee's whole contract. A
  // question in plain words lies up to 0.55 from the section that answers it ("Who reports to
  // me?"), an unrelated one ("What is the capital of France?") from 0.6 up.
  static final int TOP_K = 5;
  static final double MAX_DISTANCE = 0.6;
  // The words the platform's prompt gives the model for "me" and "my".
  static final String OWN = " (the asking user's own)";

  private static final String SEARCH =
      "SELECT title, section, body, owner_email = ? AS own FROM hr.documents WHERE"
          + " context_preparator = ? AND embedding IS NOT NULL AND (?::text IS NULL OR owner_email"
          + " = ?) AND embedding <=> ?::vector <= ? ORDER BY embedding <=> ?::vector, id LIMIT ?";

  private static final String UNINDEXED =
      "SELECT id, title, section, body FROM hr.documents WHERE context_preparator = ? AND embedding"
          + " IS NULL";

  private static final String INDEX = "UPDATE hr.documents SET embedding = ?::vector WHERE id = ?";

  @Override
  public void prepare(Exchange exchange, AIsolateManager aisolate) {
    try (Connection db = aisolate.dataSource("hr").getConnection()) {
      embedMissingSections(db, aisolate);
      String email = aisolate.principal().email() == null ? "" : aisolate.principal().email();
      String owner = ownerOf(aisolate.principal());
      String vector = toVector(aisolate.llm("nomic").embed(exchange.question()));
      List<Passage> passages = new ArrayList<>();
      try (PreparedStatement query = db.prepareStatement(SEARCH)) {
        query.setString(1, email);
        query.setString(2, CONTEXT_PREPARATOR);
        query.setString(3, owner);
        query.setString(4, owner);
        query.setString(5, vector);
        query.setDouble(6, MAX_DISTANCE);
        query.setString(7, vector);
        query.setInt(8, TOP_K);
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            String title = rows.getString("title") + (rows.getBoolean("own") ? OWN : "");
            passages.add(new Passage(title, rows.getString("section"), rows.getString("body")));
          }
        }
      }
      exchange.addEvidence(CONTEXT_PREPARATOR, passages);
    } catch (IllegalArgumentException | SQLException unavailable) {
      // Fail closed for documents: no passages, the model says it does not know. An
      // IllegalArgumentException: no data source hr, or an LlmException: no model nomic, or one
      // that cannot embed. Never a SQLException's message: it can echo the database's address.
      String cause =
          unavailable instanceof SQLException sql
              ? sql.getClass().getName() + " SQLState=" + sql.getSQLState()
              : unavailable.getMessage();
      aisolate.log().log(System.Logger.Level.WARNING, "contracts unavailable: " + cause);
    }
  }

  /**
   * The owner whose contracts the principal reads: null for the CEO, who reads every contract; for
   * anyone else their e-mail, or "" when the token carries none, which owns no contract.
   */
  static String ownerOf(Principal principal) {
    if (principal.roles().contains("ceo")) {
      return null;
    }
    return principal.email() == null ? "" : principal.email();
  }

  // Embeds on the request thread so the sample runs without an indexer; a real project
  // indexes ahead of time
  private static void embedMissingSections(Connection db, AIsolateManager aisolate)
      throws SQLException {
    try (PreparedStatement select = db.prepareStatement(UNINDEXED)) {
      select.setString(1, CONTEXT_PREPARATOR);
      try (ResultSet rows = select.executeQuery();
          PreparedStatement update = db.prepareStatement(INDEX)) {
        while (rows.next()) {
          String text =
              rows.getString("title")
                  + "\n"
                  + rows.getString("section")
                  + "\n"
                  + rows.getString("body");
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
