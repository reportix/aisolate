package com.aldermoor.ehr.pre_llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aldermoor.ehr.support.FakeAIsolateManager;
import com.aldermoor.ehr.support.FakeExchange;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Principal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionMemoryTest {

  private final SessionMemory memory = new SessionMemory();
  private final FakeAIsolateManager noah = new FakeAIsolateManager();

  @Test
  void givenFourQuestions_whenRecalled_thenTheLastThreeOldestFirstEachCutTo200() {
    // Arrange
    String longQuestion = "x".repeat(250);
    for (String question : new String[] {"first", "second", "third", longQuestion}) {
      memory.remember(new FakeExchange(question), null, noah);
    }
    FakeExchange next = new FakeExchange("and last year?");

    // Act
    memory.recall(next, noah);

    // Assert
    assertThat(next.context)
        .containsExactly(
            "The user's recent questions, oldest first: second | third | " + "x".repeat(200));
  }

  @Test
  void givenAnotherUsersQuestions_whenRecalled_thenNothingOfTheirs() {
    // Arrange
    memory.remember(new FakeExchange("What was Julian's last troponin?"), null, noah);
    FakeAIsolateManager grace =
        new FakeAIsolateManager(
            new Principal("grace", "grace@example.com", Set.of("chief")),
            new AirlockRef("test-space", "cardiology", "test"));
    FakeExchange hers = new FakeExchange("Average blood pressure by age group?");

    // Act
    memory.recall(hers, grace);

    // Assert
    assertThat(hers.context).isEmpty();
  }
}
