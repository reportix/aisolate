package com.acme.hr.pre_llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hr.support.FakeAIsolateManager;
import com.acme.hr.support.FakeExchange;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Principal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionMemoryTest {

  private final SessionMemory memory = new SessionMemory();
  private final FakeAIsolateManager eric = new FakeAIsolateManager();

  @Test
  void givenFourQuestions_whenRecalled_thenTheLastThreeOldestFirstEachCutTo200() {
    // Arrange
    String longQuestion = "x".repeat(250);
    for (String question : new String[] {"first", "second", "third", longQuestion}) {
      memory.remember(new FakeExchange(question), null, eric);
    }
    FakeExchange next = new FakeExchange("and last year?");

    // Act
    memory.recall(next, eric);

    // Assert
    assertThat(next.context)
        .containsExactly(
            "The user's recent questions, oldest first: second | third | " + "x".repeat(200));
  }

  @Test
  void givenAnotherUsersQuestions_whenRecalled_thenNothingOfTheirs() {
    // Arrange
    memory.remember(new FakeExchange("What is Nora's salary?"), null, eric);
    FakeAIsolateManager nora =
        new FakeAIsolateManager(
            new Principal("nora", "nora@example.com", Set.of("ceo")),
            new AirlockRef("test-space", "hr", "test"));
    FakeExchange hers = new FakeExchange("Average salary by department?");

    // Act
    memory.recall(hers, nora);

    // Assert
    assertThat(hers.context).isEmpty();
  }
}
