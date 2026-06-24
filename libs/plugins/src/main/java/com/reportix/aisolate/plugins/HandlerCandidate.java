package com.reportix.aisolate.plugins;

import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.ContextPreparator;
import com.reportix.aisolate.sdk.ConversationMemory;
import com.reportix.aisolate.sdk.DataSources;
import com.reportix.aisolate.sdk.Description;
import java.util.List;

/**
 * One handler class found in an airlock's jar, before the airlock lists it: its name (the class's
 * simple name, what an airlock's {@code handlers} names), stage, order, and the instance to call.
 */
public record HandlerCandidate(String name, Stage stage, int order, Object instance) {

  /** The handler stages, each found by the SDK interface its class implements. */
  public enum Stage {
    CONTEXT_PREP(ContextPreparator.class),
    MEMORY(ConversationMemory.class),
    VERIFIER(AnswerVerifier.class);

    public final Class<?> contract;

    Stage(Class<?> contract) {
      this.contract = contract;
    }

    /** The handler's own {@code order()}, the one its stage's interface declares. */
    int orderOf(Object instance) {
      return switch (this) {
        case CONTEXT_PREP -> ((ContextPreparator) instance).order();
        case MEMORY -> ((ConversationMemory) instance).order();
        case VERIFIER -> ((AnswerVerifier) instance).order();
      };
    }
  }

  public HandlerCandidate {
    if (!stage.contract.isInstance(instance)) {
      throw new IllegalArgumentException(
          name + " is scanned as " + stage + " but is no " + stage.contract.getSimpleName());
    }
  }

  /** The sentence of the class's {@link Description}, null without one. */
  public String description() {
    Description description = instance.getClass().getAnnotation(Description.class);
    return description == null ? null : description.value();
  }

  /** The ids of the class's {@link DataSources}, empty without one. */
  public List<String> dataSources() {
    DataSources dataSources = instance.getClass().getAnnotation(DataSources.class);
    return dataSources == null ? List.of() : List.of(dataSources.value());
  }
}
