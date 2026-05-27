package com.reportix.aisolate.sdk;

/**
 * Runs before the model sees the question; enriches the exchange, decides nothing else. Typically
 * it searches the developer's own store, filters by the asking principal and adds passages as
 * evidence. Found like a {@link ToolPlugin}: every concrete class implementing it is one. One
 * instance serves every exchange of the airlock, concurrently: keep it thread-safe.
 */
public interface ContextPreparator {

  /**
   * Adds evidence or context to the exchange. A {@link RuntimeException} is logged and the exchange
   * goes on without what this handler did not add.
   */
  void prepare(Exchange exchange, AIsolateManager aisolate);

  /** Where it runs among the airlock's context preparators: ascending, then by class name. */
  default int order() {
    return 0;
  }
}
