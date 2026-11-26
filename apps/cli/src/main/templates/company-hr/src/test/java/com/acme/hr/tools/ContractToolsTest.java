package com.acme.hr.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.sdk.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ContractToolsTest {

  /** Records what a tool asks of the governed query; returns no rows. */
  static final class RecordingQuery implements GovernedQuery {
    record Read(String entity, List<Filter> filters, int limit) {}

    final List<Read> calls = new ArrayList<>();

    @Override
    public Result read(String entity, List<Filter> filters, int limit) {
      calls.add(new Read(entity, filters, limit));
      return null;
    }

    @Override
    public Result aggregate(
        String entity, Fn function, String column, String groupBy, List<Filter> filters) {
      throw new UnsupportedOperationException("the plugin has no aggregate tool");
    }
  }

  private final RecordingQuery query = new RecordingQuery();

  @Test
  void givenNoArguments_whenReadContractTerms_thenEveryRowTheUserMaySee() {
    // Arrange
    Map<String, Object> args = Map.of();

    // Act
    tool("read_contract_terms").handler().call(query, args);

    // Assert
    assertThat(query.calls)
        .containsExactly(new RecordingQuery.Read("hr.contract_terms", List.of(), 50));
  }

  @Test
  void givenFilters_whenReadContractTerms_thenPassedOnAsTheyAre() {
    // Arrange
    Map<String, Object> args =
        Map.of("filters", List.of(Map.of("column", "non_compete", "op", "eq", "value", "yes")));

    // Act
    tool("read_contract_terms").handler().call(query, args);

    // Assert
    assertThat(query.calls)
        .containsExactly(
            new RecordingQuery.Read(
                "hr.contract_terms", List.of(new Filter("non_compete", Filter.Op.EQ, "yes")), 50));
  }

  static Stream<Arguments> salaryArguments() {
    return Stream.of(
        argumentSet("no arguments", Map.of(), List.of()),
        argumentSet(
            "a named person",
            Map.of(
                "filters", List.of(Map.of("column", "employee", "op", "ilike", "value", "John"))),
            List.of(new Filter("employee", Filter.Op.ILIKE, "John"))));
  }

  @ParameterizedTest
  @MethodSource("salaryArguments")
  void givenArguments_whenFetchSalary_thenReadsTheSalaryEntity(
      Map<String, Object> args, List<Filter> filters) {
    // Arrange
    Tool fetchSalary = tool("fetch_salary");

    // Act
    fetchSalary.handler().call(query, args);

    // Assert
    assertThat(query.calls).containsExactly(new RecordingQuery.Read("hr.salary", filters, 50));
    assertThat(ContractTools.SALARY.columns().keySet())
        .containsExactlyInAnyOrder("employee", "email", "salary_eur");
  }

  @Test
  void givenThePlugin_whenTheSubjectIsItsIdentity_thenTheAskingUsersRowIsMarkedSelf() {
    // Arrange: AIsolate marks a row self only when the entity's subject is the plugin's identity
    // attribute
    ContractTools plugin = new ContractTools();

    // Act
    String subject = ContractTools.TERMS.subject();
    String identity = plugin.identityAttribute();

    // Assert
    assertThat(subject).isEqualTo(identity).isEqualTo("email");
    assertThat(ContractTools.TERMS.subjectName()).isEqualTo("employee");
  }

  private static Tool tool(String name) {
    return new ContractTools()
        .tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }
}
