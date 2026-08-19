package com.reportix.aisolate.airlock.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition.HandlerSelection;
import com.reportix.aisolate.plugins.HandlerCandidate;
import com.reportix.aisolate.plugins.HandlerCandidate.Stage;
import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.ContextPreparator;
import com.reportix.aisolate.sdk.ConversationMemory;
import com.reportix.aisolate.sdk.Exchange;
import com.reportix.aisolate.sdk.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** An airlock runs the handlers of its code that it lists, and only those. */
class HandlerChainTest {

  private static final ContextPreparator PREP = (exchange, aisolate) -> {};
  private static final AnswerVerifier VERIFIER = (answer, evidence, aisolate) -> Verdict.pass("ok");
  private static final ConversationMemory MEMORY =
      new ConversationMemory() {
        @Override
        public void recall(Exchange exchange, AIsolateManager aisolate) {}

        @Override
        public void remember(Exchange exchange, Answer answer, AIsolateManager aisolate) {}
      };

  private static HandlerCandidate candidate(String name, Stage stage, int order) {
    Object instance =
        switch (stage) {
          case CONTEXT_PREP -> PREP;
          case MEMORY -> MEMORY;
          case VERIFIER -> VERIFIER;
        };
    return new HandlerCandidate(name, stage, order, instance);
  }

  private static HandlerCandidate prep(String name, int order) {
    return candidate(name, Stage.CONTEXT_PREP, order);
  }

  /** An airlock document listing {@code names} under the one stage. */
  private static HandlerSelection listing(Stage stage, String... names) {
    List<String> listed = List.of(names);
    return new HandlerSelection(
        "sha256:9f2c",
        stage == Stage.CONTEXT_PREP ? listed : List.of(),
        stage == Stage.MEMORY ? listed : List.of(),
        stage == Stage.VERIFIER ? listed : List.of());
  }

  private static List<String> names(List<HandlerCandidate> handlers) {
    return handlers.stream().map(HandlerCandidate::name).toList();
  }

  @ParameterizedTest
  @CsvSource({
    "CONTEXT_PREP, context preparator",
    "MEMORY, memory",
    "VERIFIER, verifier",
  })
  void givenNoCandidateOfTheListedName_whenResolve_thenRefusedWithTheStageWord(
      Stage stage, String word) {
    // Arrange
    HandlerSelection selection = listing(stage, "Missing");

    // Act
    Throwable thrown =
        catchThrowable(() -> HandlerChain.resolve(List.of(), "hr-acme", "hr-assistant", selection));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no " + word + " Missing in the code of hr-acme/hr-assistant");
  }

  @Test
  void givenTwoCandidatesWithOneName_whenResolve_thenRefused() {
    // Arrange: the airlock's code ships a HandbookPreparator in two packages
    List<HandlerCandidate> twice =
        List.of(prep("HandbookPreparator", 1), prep("HandbookPreparator", 2));
    HandlerSelection selection = listing(Stage.CONTEXT_PREP, "HandbookPreparator");

    // Act
    Throwable thrown =
        catchThrowable(() -> HandlerChain.resolve(twice, "hr-acme", "acme-hr", selection));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("handler HandbookPreparator is declared twice");
  }

  @Test
  void givenOrdersAndNames_whenResolve_thenAscendingOrderThenNameWhateverTheListing() {
    // Arrange
    List<HandlerCandidate> candidates =
        List.of(prep("Zeta", 1), prep("Alpha", 1), prep("First", 0));
    HandlerSelection selection = listing(Stage.CONTEXT_PREP, "Zeta", "First", "Alpha");

    // Act
    Handlers handlers = HandlerChain.resolve(candidates, "hr-acme", "acme-hr", selection);

    // Assert
    assertThat(names(handlers.contextPreparations())).containsExactly("First", "Alpha", "Zeta");
  }

  @Test
  void givenUnlistedCandidates_whenResolve_thenTheyDoNotRun() {
    // Arrange
    List<HandlerCandidate> candidates =
        List.of(prep("Glossary", 1), prep("Handbook", 1), candidate("Session", Stage.MEMORY, 1));

    // Act
    Handlers listed =
        HandlerChain.resolve(
            candidates, "hr-acme", "acme-hr", listing(Stage.CONTEXT_PREP, "Handbook"));
    Handlers none =
        HandlerChain.resolve(
            candidates, "hr-acme", "acme-hr", new HandlerSelection(null, null, null, null));

    // Assert
    assertThat(names(listed.contextPreparations())).containsExactly("Handbook");
    assertThat(listed.memories()).isEmpty();
    assertThat(none).isEqualTo(new Handlers(List.of(), List.of(), List.of()));
  }

  @Test
  void givenEveryStage_whenResolve_thenEachLandsInItsOwnList() {
    // Arrange
    List<HandlerCandidate> candidates =
        List.of(
            prep("Handbook", 1),
            candidate("Session", Stage.MEMORY, 1),
            candidate("Salary", Stage.VERIFIER, 1));
    HandlerSelection selection =
        new HandlerSelection(
            "sha256:9f2c", List.of("Handbook"), List.of("Session"), List.of("Salary"));

    // Act
    Handlers handlers = HandlerChain.resolve(candidates, "hr-acme", "acme-hr", selection);

    // Assert
    assertThat(names(handlers.contextPreparations())).containsExactly("Handbook");
    assertThat(names(handlers.memories())).containsExactly("Session");
    assertThat(names(handlers.verifiers())).containsExactly("Salary");
  }
}
