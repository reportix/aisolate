package com.acme.sales.pre_llm;

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
 * Retrieval over Acme's client contracts and board memos, read from the data source {@code clients}
 * of {@code aisolate.yaml}: the question is embedded with the LLM {@code nomic}, the closest
 * sections are added to the exchange under one evidence id. It filters nothing, by design: everyone
 * the airlock admits reads every section, board memos included. Who may ask this airlock is its
 * space's roles and its own, so a role put on the space to ask another airlock admits its holders
 * here too. The role lists are the boundary here: put on the space only roles whose holders may
 * read every document of every airlock. One instance serves every exchange concurrently: no fields,
 * one connection per call.
 */
@Description("Read client contracts")
@DataSources("clients")
public final class SalesDocumentsContextPreparator implements ContextPreparator {
  @Override
  public int order() {
    return 1;
  }

  static final String CONTEXT_PREPARATOR = "client-documents";
  // At most three sections, and only close ones: every passage is prompt the model reads before it
  // answers. Over the demo's documents a relevant section lies under 0.35, noise from 0.41 up.
  static final int TOP_K = 3;
  static final double MAX_DISTANCE = 0.35;

  private static final String SEARCH =
      "SELECT title, section, body FROM clients.documents WHERE context_preparator = ? AND"
          + " embedding IS NOT NULL AND embedding <=> ?::vector <= ? ORDER BY embedding <=>"
          + " ?::vector, id LIMIT ?";

  private static final String UNINDEXED =
      "SELECT id, title, section, body FROM clients.documents WHERE context_preparator = ? AND"
          + " embedding IS NULL";

  private static final String INDEX =
      "UPDATE clients.documents SET embedding = ?::vector WHERE id = ?";

  @Override
  public void prepare(Exchange exchange, AIsolateManager aisolate) {
    try (Connection db = aisolate.dataSource("clients").getConnection()) {
      embedMissingSections(db, aisolate);
      String vector = toVector(aisolate.llm("nomic").embed(exchange.question()));
      List<Passage> passages = new ArrayList<>();
      try (PreparedStatement query = db.prepareStatement(SEARCH)) {
        query.setString(1, CONTEXT_PREPARATOR);
        query.setString(2, vector);
        query.setDouble(3, MAX_DISTANCE);
        query.setString(4, vector);
        query.setInt(5, TOP_K);
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
      // Fail closed for documents: no passages, the model says it does not know. An
      // IllegalArgumentException: no data source clients, or an LlmException: no model nomic, or
      // one that cannot embed. Never a SQLException's message: it can echo the database's address.
      String cause =
          unavailable instanceof SQLException sql
              ? sql.getClass().getName() + " SQLState=" + sql.getSQLState()
              : unavailable.getMessage();
      aisolate.log().log(System.Logger.Level.WARNING, "client-documents unavailable: " + cause);
    }
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
