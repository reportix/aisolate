package com.reportix.aisolate.airlock.domain.chat;

import com.reportix.aisolate.plugins.HandlerCandidate;
import java.util.List;

/** The handlers one airlock runs, each list in execution order. */
public record Handlers(
    List<HandlerCandidate> contextPreparations,
    List<HandlerCandidate> memories,
    List<HandlerCandidate> verifiers) {

  public Handlers {
    contextPreparations = List.copyOf(contextPreparations);
    memories = List.copyOf(memories);
    verifiers = List.copyOf(verifiers);
  }
}
