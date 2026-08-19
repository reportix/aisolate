package com.reportix.aisolate.airlock.support;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.HandlerCatalog;
import com.reportix.aisolate.airlock.domain.chat.Handlers;
import com.reportix.aisolate.plugins.HandlerCandidate;
import com.reportix.aisolate.plugins.HandlerCandidate.Stage;
import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.ContextPreparator;
import com.reportix.aisolate.sdk.ConversationMemory;
import java.util.ArrayList;
import java.util.List;

/** Serves the handlers a test registered, or fails the way a broken jar does. */
public final class FakeHandlerCatalog implements HandlerCatalog {

  private final List<HandlerCandidate> contextPreparations = new ArrayList<>();
  private final List<HandlerCandidate> memories = new ArrayList<>();
  private final List<HandlerCandidate> verifiers = new ArrayList<>();
  private RuntimeException failure;

  public FakeHandlerCatalog contextPreparation(String name, int order, ContextPreparator handler) {
    contextPreparations.add(new HandlerCandidate(name, Stage.CONTEXT_PREP, order, handler));
    return this;
  }

  public FakeHandlerCatalog memory(String name, int order, ConversationMemory handler) {
    memories.add(new HandlerCandidate(name, Stage.MEMORY, order, handler));
    return this;
  }

  public FakeHandlerCatalog verifier(String name, int order, AnswerVerifier handler) {
    verifiers.add(new HandlerCandidate(name, Stage.VERIFIER, order, handler));
    return this;
  }

  public FakeHandlerCatalog failing(RuntimeException failure) {
    this.failure = failure;
    return this;
  }

  @Override
  public Handlers resolveHandlers(AirlockDefinition airlock) {
    if (failure != null) {
      throw failure;
    }
    return new Handlers(contextPreparations, memories, verifiers);
  }
}
