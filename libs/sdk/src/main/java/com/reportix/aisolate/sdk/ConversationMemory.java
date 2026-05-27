package com.reportix.aisolate.sdk;

/**
 * Session memory as the developer keeps it; AIsolate stores nothing for it. It recalls into the
 * context after the context preparators ran, and remembers after the answer went out. Found like a
 * {@link ToolPlugin}: every concrete class implementing it is one. One instance serves every
 * exchange of the airlock, concurrently: keep it thread-safe.
 */
public interface ConversationMemory {

  /** Before the model: add what this user's history says, as context. */
  void recall(Exchange exchange, AIsolateManager aisolate);

  /** After the answer went out, whatever the verdicts were. Failures are logged, nothing else. */
  void remember(Exchange exchange, Answer answer, AIsolateManager aisolate);

  /** Where it runs among the airlock's conversation memories: ascending, then by class name. */
  default int order() {
    return 0;
  }
}
