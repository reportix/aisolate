package com.acme.sales.pre_llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.sales.support.FakeAIsolateManager;
import com.acme.sales.support.FakeExchange;
import org.junit.jupiter.api.Test;

class SalesDocumentsContextPreparatorTest {

  @Test
  void givenNoDataSourceClients_whenPrepare_thenNoPassagesAndNoError() {
    // Arrange: the fake defines no data source, as an airlock without clients would
    FakeExchange exchange = new FakeExchange("What did we agree with Varnholt on the price cap?");

    // Act
    new SalesDocumentsContextPreparator().prepare(exchange, new FakeAIsolateManager());

    // Assert
    assertThat(exchange.evidence).isEmpty();
    assertThat(exchange.context).isEmpty();
  }

  @Test
  void givenEmbedding_whenToVector_thenPgvectorLiteral() {
    // Arrange
    float[] embedding = {0.5f, -1.0f, 2.25f};

    // Act
    String vector = SalesDocumentsContextPreparator.toVector(embedding);

    // Assert
    assertThat(vector).isEqualTo("[0.5,-1.0,2.25]");
  }
}
