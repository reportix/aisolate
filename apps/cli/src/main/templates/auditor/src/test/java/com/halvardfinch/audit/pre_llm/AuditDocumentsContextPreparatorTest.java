package com.halvardfinch.audit.pre_llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.halvardfinch.audit.support.FakeAIsolateManager;
import com.halvardfinch.audit.support.FakeExchange;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Principal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuditDocumentsContextPreparatorTest {

  @ParameterizedTest
  @ValueSource(strings = {"employee", "chief", "audit-trainee"})
  void givenNoAuditorRole_whenPrepare_thenTheDatabaseIsNeverOpened(String role) {
    // Arrange
    FakeAIsolateManager clerk =
        new FakeAIsolateManager(
            new Principal("clerk", "clerk@example.com", Set.of(role)),
            new AirlockRef("test-space", "audit", "test"));
    FakeExchange exchange = new FakeExchange("What did the board decide on the warehouse lease?");

    // Act
    new AuditDocumentsContextPreparator().prepare(exchange, clerk);

    // Assert
    assertThat(clerk.dataSourcesAsked).isEmpty();
    assertThat(exchange.evidence).isEmpty();
  }

  @Test
  void givenAuditorAndNoDataSourceLedger_whenPrepare_thenNoPassagesAndNoError() {
    // Arrange: the fake defines no data source, as an airlock without ledger would
    FakeAIsolateManager hannah = new FakeAIsolateManager();
    FakeExchange exchange = new FakeExchange("What did the board decide on the warehouse lease?");

    // Act
    new AuditDocumentsContextPreparator().prepare(exchange, hannah);

    // Assert
    assertThat(hannah.dataSourcesAsked).containsExactly("ledger");
    assertThat(exchange.evidence).isEmpty();
    assertThat(exchange.context).isEmpty();
  }

  @Test
  void givenEmbedding_whenToVector_thenPgvectorLiteral() {
    // Arrange
    float[] embedding = {0.5f, -1.0f, 2.25f};

    // Act
    String vector = AuditDocumentsContextPreparator.toVector(embedding);

    // Assert
    assertThat(vector).isEqualTo("[0.5,-1.0,2.25]");
  }
}
