package com.reportix.aisolate.sdk;

import java.util.List;

/**
 * Checks the answer against the evidence the exchange gathered. A verdict annotates the answer in
 * the browser; it never rewrites or blocks it. A thrown {@link RuntimeException} becomes a {@code
 * WARN} verdict. Found like a {@link ToolPlugin}: every concrete class implementing it is one. One
 * instance serves every exchange of the airlock, concurrently: keep it thread-safe.
 */
public interface AnswerVerifier {

  /**
   * @param answer the text the user gets, with the verdicts of the verifiers that ran before, the
   *     question as typed and the grounded answer as JSON text
   * @param evidence every passage and tool result of this exchange, rows included
   */
  Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager aisolate);

  /** Where it runs among the airlock's verifiers: ascending, then by class name. */
  default int order() {
    return 0;
  }
}
