package com.reportix.aisolate.airlock.domain.chat;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition.HandlerSelection;
import com.reportix.aisolate.plugins.HandlerCandidate;
import com.reportix.aisolate.plugins.HandlerCandidate.Stage;
import java.util.Comparator;
import java.util.List;

/**
 * Which of the handler candidates of an airlock's code it runs: the ones its document lists, each
 * the one candidate of that stage and name. A listed name the code does not carry, or carries
 * twice, is a developer error. Within a stage they run by {@code order}, then name.
 */
public final class HandlerChain {

  private static final Comparator<HandlerCandidate> BY_ORDER_THEN_NAME =
      Comparator.comparingInt(HandlerCandidate::order).thenComparing(HandlerCandidate::name);

  /**
   * @param candidates the handlers of the airlock's code, and of nothing else
   */
  public static Handlers resolve(
      List<HandlerCandidate> candidates,
      String spaceId,
      String airlockId,
      HandlerSelection selection) {
    String code = spaceId + "/" + airlockId;
    return new Handlers(
        select(candidates, Stage.CONTEXT_PREP, selection.contextPreparators(), code),
        select(candidates, Stage.MEMORY, selection.memories(), code),
        select(candidates, Stage.VERIFIER, selection.verifiers(), code));
  }

  private static List<HandlerCandidate> select(
      List<HandlerCandidate> candidates, Stage stage, List<String> names, String code) {
    return names.stream()
        .map(name -> find(candidates, stage, name, code))
        .sorted(BY_ORDER_THEN_NAME)
        .toList();
  }

  private static HandlerCandidate find(
      List<HandlerCandidate> candidates, Stage stage, String name, String code) {
    List<HandlerCandidate> named =
        candidates.stream()
            .filter(candidate -> candidate.stage() == stage && candidate.name().equals(name))
            .toList();
    if (named.isEmpty()) {
      throw new IllegalStateException("no " + word(stage) + " " + name + " in the code of " + code);
    }
    if (named.size() > 1) {
      throw new IllegalStateException("handler " + name + " is declared twice");
    }
    return named.getFirst();
  }

  private static String word(Stage stage) {
    return switch (stage) {
      case CONTEXT_PREP -> "context preparator";
      case MEMORY -> "memory";
      case VERIFIER -> "verifier";
    };
  }

  private HandlerChain() {}
}
