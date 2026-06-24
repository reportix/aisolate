package com.reportix.aisolate.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CatalogCheckTest {

  private static final Entity COMPENSATION =
      new Entity(
          "hr.compensation",
          "hr.compensation",
          "employee_id",
          new Column("employee_id", "employee_id", Column.Type.NUMBER),
          new Column("iban", "iban", Column.Type.TEXT));

  private static ToolPlugin plugin(
      String name, String dataSource, List<Entity> entities, String... tools) {
    return new ToolPlugin() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public List<Entity> entities() {
        return entities;
      }

      @Override
      public String dataSource() {
        return dataSource;
      }

      @Override
      public List<Tool> tools() {
        return Stream.of(tools).map(tool -> new Tool(tool, "", Map.of(), (q, a) -> null)).toList();
      }
    };
  }

  private static RuleSet rule(String entity, String fields) {
    return RuleSet.parse(
        """
        {"entity":"%s","classification":"restricted","rules":[
          {"name":"r","principals":{"role":"ceo"},"actions":["read"],"effect":"allow",%s}]}
        """
            .formatted(entity, fields));
  }

  static Stream<Arguments> notOneCatalog() {
    ToolPlugin hr = plugin("hr-tools", "hr", List.of(COMPENSATION), "read_records");
    return Stream.of(
        arguments(
            named(
                "one tool name twice",
                List.of(hr, plugin("other", "hr", List.of(), "read_records"))),
            List.of(),
            "tool read_records is published twice"),
        arguments(
            named("one entity twice", List.of(hr, plugin("other", "hr", List.of(COMPENSATION)))),
            List.of(),
            "entity hr.compensation is declared twice"),
        arguments(
            named(
                "entities without a data source",
                List.of(plugin("hr-tools", " ", List.of(COMPENSATION)))),
            List.of(),
            "plugin hr-tools declares entities but names no data source"),
        arguments(
            named(
                "a row key that is not a column",
                List.of(
                    plugin(
                        "hr-tools",
                        "hr",
                        List.of(
                            new Entity("hr.leave", "hr.leave", "id", COMPENSATION.columns()))))),
            List.of(),
            "entity hr.leave: row key id is not a column"),
        arguments(
            named(
                "a subject that is not a column",
                List.of(plugin("hr-tools", "hr", List.of(COMPENSATION.withSubject("patient_id"))))),
            List.of(),
            "entity hr.compensation: subject patient_id is not a column"),
        arguments(
            named("two rule sets for one entity", List.of(hr)),
            List.of(
                rule("hr.compensation", "\"row_filter\":\"true\""),
                rule("hr.compensation", "\"row_filter\":\"true\"")),
            "two rule sets for entity hr.compensation"),
        // "ibn" for "iban" would otherwise leave iban in the clear.
        arguments(
            named("a misspelt column obligation", List.of(hr)),
            List.of(
                rule("hr.compensation", "\"row_filter\":\"true\",\"columns\":{\"ibn\":\"mask\"}")),
            "rule set hr.compensation: rule r names column ibn no plugin declares"),
        arguments(
            named("a row filter on an unknown column", List.of(hr)),
            List.of(rule("hr.compensation", "\"row_filter\":\"emp_id == principal.employee_id\"")),
            "rule set hr.compensation: rule r names column emp_id no plugin declares"),
        arguments(
            named("a blank plugin name", List.of(plugin(" ", "hr", List.of(COMPENSATION)))),
            List.of(),
            "a plugin has no name"),
        arguments(
            named("a blank tool name", List.of(plugin("hr-tools", "hr", List.of(), ""))),
            List.of(),
            "a tool of plugin hr-tools has no name"),
        // SQL folds an unquoted grossSalary to grosssalary: the result would carry another name.
        arguments(
            named(
                "a column name SQL folds",
                List.of(
                    plugin(
                        "hr-tools",
                        "hr",
                        List.of(
                            new Entity(
                                "hr.pay",
                                "hr.pay",
                                "id",
                                new Column("id", "id", Column.Type.NUMBER),
                                new Column("grossSalary", "gross", Column.Type.NUMBER)))))),
            List.of(),
            "entity hr.pay: column grossSalary must be lowercase letters, digits and _, starting"
                + " with a letter or _"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("notOneCatalog")
  void givenPluginsAndRulesThatAreNotOneCatalog_whenChecked_thenRefusedNamingTheProblem(
      List<ToolPlugin> plugins, List<RuleSet> ruleSets, String message) {
    // Arrange: the row's plugins and rule sets

    // Act
    Throwable thrown = catchThrowable(() -> CatalogCheck.check(plugins, ruleSets));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage(message);
  }

  @Test
  void givenColumnKeyedUnderAnotherName_whenTheEntityIsBuilt_thenRefused() {
    // Arrange: the model and the rules would know it as salary, its record as iban
    Map<String, Column> columns =
        Map.of(
            "employee_id",
            new Column("employee_id", "employee_id", Column.Type.NUMBER),
            "salary",
            new Column("iban", "iban", Column.Type.TEXT));

    // Act
    Throwable thrown =
        catchThrowable(
            () -> new Entity("hr.compensation", "hr.compensation", "employee_id", columns));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("entity hr.compensation: column iban is keyed salary");
  }

  @Test
  void givenTwoColumnsOfOneName_whenTheEntityIsBuilt_thenRefused() {
    // Arrange
    Column first = new Column("iban", "c.iban", Column.Type.TEXT);
    Column second = new Column("iban", "e.iban", Column.Type.TEXT);

    // Act
    Throwable thrown =
        catchThrowable(
            () -> new Entity("hr.compensation", "hr.compensation", "iban", first, second));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("entity hr.compensation: column iban is declared twice");
  }

  @Test
  void givenRulesOnDeclaredColumnsAndAnUndeclaredEntity_whenChecked_thenAccepted() {
    // Arrange: no call reaches a rule set whose entity no plugin declares
    List<ToolPlugin> plugins =
        List.of(plugin("hr-tools", "hr", List.of(COMPENSATION), "read_records"));
    List<RuleSet> ruleSets =
        List.of(
            rule(
                "hr.compensation",
                "\"row_filter\":\"employee_id =="
                    + " principal.employee_id\",\"columns\":{\"iban\":\"mask\"}"),
            rule("hr.archive", "\"row_filter\":\"anything == 'x'\""));

    // Act
    Throwable thrown = catchThrowable(() -> CatalogCheck.check(plugins, ruleSets));

    // Assert
    assertThat(thrown).isNull();
  }
}
