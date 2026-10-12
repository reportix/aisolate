package com.halvardfinch.audit.pre_llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.halvardfinch.audit.support.FakeAIsolateManager;
import com.halvardfinch.audit.support.FakeExchange;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Principal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionMemoryTest {

  private final SessionMemory memory = new SessionMemory();
  private final FakeAIsolateManager hannah = new FakeAIsolateManager();

  @Test
  void givenFourQuestions_whenRecalled_thenTheLastThreeOldestFirstEachCutTo200() {
    // Arrange
    String longQuestion = "x".repeat(250);
    for (String question : new String[] {"first", "second", "third", longQuestion}) {
      memory.remember(new FakeExchange(question), null, hannah);
    }
    FakeExchange next = new FakeExchange("and last year?");

    // Act
    memory.recall(next, hannah);

    // Assert
    assertThat(next.context)
        .containsExactly(
            "The user's recent questions, oldest first: second | third | " + "x".repeat(200));
  }

  @Test
  void givenAnotherUsersQuestions_whenRecalled_thenNothingOfTheirs() {
    // Arrange
    memory.remember(new FakeExchange("Which invoices have no contract?"), null, hannah);
    FakeAIsolateManager noah =
        new FakeAIsolateManager(
            new Principal("noah", "noah@example.com", Set.of("auditor")),
            new AirlockRef("test-space", "audit", "test"));
    FakeExchange his = new FakeExchange("Payments to related parties above 10,000 EUR?");

    // Act
    memory.recall(his, noah);

    // Assert
    assertThat(his.context).isEmpty();
  }
}
