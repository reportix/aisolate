package com.acme.sales.pre_llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.sales.support.FakeAIsolateManager;
import com.acme.sales.support.FakeExchange;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Principal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionMemoryTest {

  private final SessionMemory memory = new SessionMemory();
  private final FakeAIsolateManager leo = new FakeAIsolateManager();

  @Test
  void givenFourQuestions_whenRecalled_thenTheLastThreeOldestFirstEachCutTo200() {
    // Arrange
    String longQuestion = "x".repeat(250);
    for (String question : new String[] {"first", "second", "third", longQuestion}) {
      memory.remember(new FakeExchange(question), null, leo);
    }
    FakeExchange next = new FakeExchange("and last year?");

    // Act
    memory.recall(next, leo);

    // Assert
    assertThat(next.context)
        .containsExactly(
            "The user's recent questions, oldest first: second | third | " + "x".repeat(200));
  }

  @Test
  void givenAnotherUsersQuestions_whenRecalled_thenNothingOfTheirs() {
    // Arrange
    memory.remember(new FakeExchange("Which invoices are overdue?"), null, leo);
    FakeAIsolateManager nora =
        new FakeAIsolateManager(
            new Principal("nora", "nora@example.com", Set.of("founder")),
            new AirlockRef("test-space", "sales", "test"));
    FakeExchange hers = new FakeExchange("Total contract value by industry?");

    // Act
    memory.recall(hers, nora);

    // Assert
    assertThat(hers.context).isEmpty();
  }
}
