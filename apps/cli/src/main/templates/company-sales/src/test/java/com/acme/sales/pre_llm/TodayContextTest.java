package com.acme.sales.pre_llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.sales.support.FakeAIsolateManager;
import com.acme.sales.support.FakeExchange;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TodayContextTest {

  @Test
  void givenAnyQuestion_whenPrepared_thenOneLineWithTodaysIsoDateAndNoEvidence() {
    // Arrange
    FakeExchange exchange = new FakeExchange("Which invoices are overdue?");
    LocalDate before = LocalDate.now();

    // Act
    new TodayContext().prepare(exchange, new FakeAIsolateManager());

    // Assert: a run across midnight sees one of the two dates
    assertThat(exchange.context)
        .singleElement()
        .isIn(
            "Today is " + before + " (ISO date).", "Today is " + LocalDate.now() + " (ISO date).");
    assertThat(exchange.evidence).isEmpty();
  }
}
