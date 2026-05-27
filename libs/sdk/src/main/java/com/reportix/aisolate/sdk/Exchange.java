package com.reportix.aisolate.sdk;

import java.util.List;

/** One chat exchange as a handler may read and enrich it. Not thread-safe; request thread only. */
public interface Exchange {

  /** The user's message as typed. */
  String question();

  /**
   * Puts passages into the model's context under one evidence id ({@code ev-n}) and tells the
   * browser that {@code source} contributed {@code passages.size()} of them, text withheld.
   *
   * @param source what found them, for example the context preparator's id; shown in the browser
   * @return the evidence id, or null when {@code passages} is empty: nothing is added then
   */
  String addEvidence(String source, List<Passage> passages);

  /**
   * Adds free text to what the model reads before the question, a memory summary or a glossary: a
   * user-role message with the passages, data to the model rather than the system's instruction.
   */
  void addContext(String text);

  /** Every evidence of this exchange so far, passages and tool results, content included. */
  List<Evidence> evidence();
}
