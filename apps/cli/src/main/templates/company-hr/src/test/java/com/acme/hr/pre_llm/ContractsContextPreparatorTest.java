package com.acme.hr.pre_llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.hr.support.FakeAIsolateManager;
import com.acme.hr.support.FakeExchange;
import com.acme.hr.support.RecordingDataSource;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContractsContextPreparatorTest {

  @ParameterizedTest
  @ValueSource(strings = {"ceo", "employee ceo"})
  void givenTheCeo_whenOwnerOf_thenNoOwnerFilterSoEveryContract(String roles) {
    // Arrange
    Principal jane = new Principal("jane", "jane@acme.example", Set.of(roles.split(" ")));

    // Act
    String owner = ContractsContextPreparator.ownerOf(jane);

    // Assert
    assertThat(owner).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"employee", "hr"})
  void givenAnyoneElse_whenOwnerOf_thenTheirOwnContractOnly(String role) {
    // Arrange
    Principal john = new Principal("john", "john@acme.example", Set.of(role));

    // Act
    String owner = ContractsContextPreparator.ownerOf(john);

    // Assert
    assertThat(owner).isEqualTo("john@acme.example");
  }

  @Test
  void givenAnEmployeeWithoutEmail_whenOwnerOf_thenNoContract() {
    // Arrange
    Principal anonymous = new Principal("someone", null, Set.of("employee"));

    // Act
    String owner = ContractsContextPreparator.ownerOf(anonymous);

    // Assert
    assertThat(owner).isEmpty();
  }

  @Test
  void givenNoDataSourceHr_whenPrepare_thenNoPassagesAndNoError() {
    // Arrange: the fake defines no data source, as an airlock without hr would
    FakeExchange exchange = new FakeExchange("What is my notice period?");

    // Act
    new ContractsContextPreparator().prepare(exchange, new FakeAIsolateManager());

    // Assert
    assertThat(exchange.evidence).isEmpty();
    assertThat(exchange.context).isEmpty();
  }

  @Test
  void givenTheAskingUsersContract_whenPrepare_thenTheModelIsToldWhoAsks() {
    // Arrange: the CEO reads every contract, so the passages name others too; her own row names her
    RecordingDataSource hr =
        new RecordingDataSource(
            Map.of("jane@acme.example", List.<Object[]>of(new Object[] {"Jane Doe"})));
    FakeAIsolateManager jane =
        new FakeAIsolateManager(
            new Principal("jane", "jane@acme.example", Set.of("ceo")),
            new AirlockRef("test-space", "hr", "v1", "test"),
            hr.dataSource());
    FakeExchange exchange = new FakeExchange("What is my salary?");

    // Act
    new ContractsContextPreparator().prepare(exchange, jane);

    // Assert
    assertThat(exchange.context)
        .containsExactly(
            "The asking user is Jane Doe (jane@acme.example). A question about me, my or I is about"
                + " this person alone: call the tool with no filters, their row is the one marked"
                + " self, their passages the ones marked as theirs. Never filter by a name from the"
                + " passages for such a question.");
  }

  @Test
  void givenEmbedding_whenToVector_thenPgvectorLiteral() {
    // Arrange
    float[] embedding = {0.5f, -1.0f, 2.25f};

    // Act
    String vector = ContractsContextPreparator.toVector(embedding);

    // Assert
    assertThat(vector).isEqualTo("[0.5,-1.0,2.25]");
  }
}
