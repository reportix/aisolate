package com.reportix.aisolate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigurationTest {

  @ParameterizedTest
  @CsvSource({
    "global, global",
    "global space, space",
    "global airlock, airlock",
    "space airlock, airlock",
    "global space airlock, airlock",
    "airlock, airlock",
    "'', ''"
  })
  void givenGraniteAndHrOnLevels_whenResolved_thenTheNearestWinsWholeBesideEveryLevelsOwn(
      String levels, String nearest) {
    // Arrange: each listed level defines granite and hr; every level an id of its own
    List<String> defining = Arrays.asList(levels.split(" "));
    Configuration configuration =
        buildConfiguration(
            level ->
                defining.contains(level)
                    ? List.of(llm("granite", level), llm(level, level))
                    : List.of(llm(level, level)),
            level ->
                defining.contains(level)
                    ? List.of(dataSource("hr", level), dataSource(level, level))
                    : List.of(dataSource(level, level)));

    // Act
    List<Llm> llms = configuration.llmsOf("hr-assistant");
    Llm main = configuration.mainLlmOf("hr-assistant");
    List<DataSource> dataSources = configuration.dataSourcesOf("hr-assistant");

    // Assert: sorted by id, one entry per id
    List<String> own = List.of("airlock", "global", "space");
    assertThat(llms)
        .containsExactlyElementsOf(
            Stream.concat(
                    own.stream().map(level -> llm(level, level)),
                    nearest.isEmpty() ? Stream.empty() : Stream.of(llm("granite", nearest)))
                .sorted(Comparator.comparing(Llm::id))
                .toList());
    assertThat(main).isEqualTo(nearest.isEmpty() ? null : llm("granite", nearest));
    assertThat(dataSources)
        .containsExactlyElementsOf(
            Stream.concat(
                    own.stream().map(level -> dataSource(level, level)),
                    nearest.isEmpty() ? Stream.empty() : Stream.of(dataSource("hr", nearest)))
                .sorted(Comparator.comparing(DataSource::id))
                .toList());
  }

  @Test
  void givenRoleOnSpace_whenAccessOf_thenMergedDistinct() {
    // Arrange: ceo is on both levels, employee on hr only, CEO another role
    Configuration configuration =
        new Configuration(
            "rev",
            null,
            Map.of(
                "acme",
                new SpaceDocument(
                    new Metadata("acme"),
                    new SpaceDocument.Spec(
                        null, null, new SpaceDocument.Access(List.of("ceo", "hr")), null, null))),
            Map.of(
                "hr",
                buildAirlock(
                    "hr",
                    new AirlockDocument.Access(List.of("employee", "ceo", "CEO"), List.of("*"))),
                "sales",
                buildAirlock("sales", new AirlockDocument.Access(List.of(), List.of("*")))));

    // Act
    List<String> hr = configuration.accessOf("hr").roles();
    List<String> sales = configuration.accessOf("sales").roles();

    // Assert
    assertThat(hr).containsExactly("ceo", "hr", "employee", "CEO");
    assertThat(sales).containsExactly("ceo", "hr");
  }

  static Stream<Arguments> lookupsOfUnknownNames() {
    Function<Configuration, Object> spaceOf = configuration -> configuration.spaceOf("nowhere");
    Function<Configuration, Object> llmsOf = configuration -> configuration.llmsOf("nowhere");
    // The other lookups go through these
    return Stream.of(arguments(named("spaceOf", spaceOf)), arguments(named("llmsOf", llmsOf)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lookupsOfUnknownNames")
  void givenUnknownName_whenLookedUp_thenRefusedNamingIt(Function<Configuration, Object> lookup) {
    // Arrange
    Configuration configuration = buildConfiguration(level -> List.of(), level -> List.of());

    // Act
    Throwable thrown = catchThrowable(() -> lookup.apply(configuration));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no Airlock nowhere");
  }

  /** One space and airlock, plus the global level, each given its lists by level. */
  private static Configuration buildConfiguration(
      Function<String, List<Llm>> llms, Function<String, List<DataSource>> dataSources) {
    return new Configuration(
        "rev",
        new GlobalDocument(
            new Metadata("aisolate"),
            new GlobalDocument.Spec(llms.apply("global"), dataSources.apply("global"))),
        Map.of(
            "hr-acme",
            new SpaceDocument(
                new Metadata("hr-acme"),
                new SpaceDocument.Spec(
                    null, null, null, llms.apply("space"), dataSources.apply("space")))),
        Map.of(
            "hr-assistant",
            new AirlockDocument(
                new Metadata("hr-assistant"),
                new AirlockDocument.Spec(
                    "hr-acme",
                    null,
                    null,
                    null,
                    null,
                    "granite",
                    "Answer.",
                    null,
                    null,
                    llms.apply("airlock"),
                    dataSources.apply("airlock"),
                    null))));
  }

  private static AirlockDocument buildAirlock(String name, AirlockDocument.Access access) {
    return new AirlockDocument(
        new Metadata(name),
        new AirlockDocument.Spec(
            "acme", null, null, access, null, null, null, null, null, null, null, null));
  }

  private static Llm llm(String id, String owner) {
    return new Llm(id, "ollama", "http://" + owner + "-llm:11434", owner + "-model");
  }

  private static DataSource dataSource(String id, String owner) {
    return new DataSource(id, "jdbc:postgresql://" + owner + "-db:5432/hr", owner + "-ds-" + id);
  }
}
