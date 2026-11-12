package com.reportix.aisolate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

  @Test
  void givenVersionsOfTwoAirlocks_whenLookedUp_thenEachAirlockHasItsOwnInDocumentOrder() {
    // Arrange
    Map<String, AirlockVersionDocument> versions = new LinkedHashMap<>();
    versions.put("hr-assistant-v2", buildVersion("hr-assistant", "v2"));
    versions.put("founders-finance-v1", buildVersion("founders-finance", "v1"));
    versions.put("hr-assistant-v1", buildVersion("hr-assistant", "v1"));
    Configuration configuration =
        new Configuration(
            "rev",
            null,
            Map.of(
                "hr-acme",
                new SpaceDocument(
                    new Metadata("hr-acme"), new SpaceDocument.Spec(null, null, null, null, null))),
            Map.of(
                "hr-assistant", buildAirlock("hr-assistant", null),
                "founders-finance", buildAirlock("founders-finance", null)),
            versions);

    // Act / Assert
    assertThat(configuration.versionsOf("hr-assistant"))
        .extracting(AirlockVersionDocument::name)
        .containsExactly("hr-assistant-v2", "hr-assistant-v1");
    assertThat(configuration.airlockOf("hr-assistant-v2").name()).isEqualTo("hr-assistant");
    assertThat(configuration.spaceOf("founders-finance").name()).isEqualTo("hr-acme");
  }

  @ParameterizedTest
  @CsvSource({"v2, v10, -1", "pilot, 2026-10, 1", "v1, v1, 0", "v01, v1, -1"})
  void givenVersionNames_whenSorted_thenDigitRunsCompareAsNumbers(
      String first, String second, int expected) {
    // Arrange: equal names compare as 0, so a sort leaves them where they are
    Comparator<String> order = AirlockVersionDocument.VERSION_ORDER;

    // Act
    int compared = Integer.signum(order.compare(first, second));

    // Assert
    assertThat(compared).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "global, global, global",
    "global space, space, space",
    "global airlock, airlock, airlock",
    "space airlock, airlock, airlock",
    "global space airlock version, version, airlock",
    "version, version, ''",
    "'', '', ''"
  })
  void givenGraniteAndHrOnLevels_whenResolved_thenTheNearestWinsWholeBesideEveryLevelsOwn(
      String levels, String nearestLlm, String nearestDataSource) {
    // Arrange: each listed level defines granite and hr; every level an id of its own. A version
    // holds no data source, so hr stops at the airlock.
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
    List<Llm> llms = configuration.llmsOf("hr-assistant-v1");
    Llm main = configuration.mainLlmOf("hr-assistant-v1");
    List<DataSource> dataSources = configuration.dataSourcesOf("hr-assistant-v1");

    // Assert: sorted by id, one entry per id
    assertThat(llms)
        .containsExactlyElementsOf(
            Stream.concat(
                    Stream.of("airlock", "global", "space", "version")
                        .map(level -> llm(level, level)),
                    nearestLlm.isEmpty() ? Stream.empty() : Stream.of(llm("granite", nearestLlm)))
                .sorted(Comparator.comparing(Llm::id))
                .toList());
    assertThat(main).isEqualTo(nearestLlm.isEmpty() ? null : llm("granite", nearestLlm));
    assertThat(dataSources)
        .containsExactlyElementsOf(
            Stream.concat(
                    Stream.of("airlock", "global", "space").map(level -> dataSource(level, level)),
                    nearestDataSource.isEmpty()
                        ? Stream.empty()
                        : Stream.of(dataSource("hr", nearestDataSource)))
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
                "hr-acme",
                new SpaceDocument(
                    new Metadata("hr-acme"),
                    new SpaceDocument.Spec(
                        null, null, new SpaceDocument.Access(List.of("ceo", "hr")), null, null))),
            Map.of(
                "hr",
                buildAirlock(
                    "hr",
                    new AirlockDocument.Access(List.of("employee", "ceo", "CEO"), List.of("*"))),
                "sales",
                buildAirlock("sales", new AirlockDocument.Access(List.of(), List.of("*")))),
            Map.of());

    // Act
    List<String> hr = configuration.accessOf("hr").roles();
    List<String> sales = configuration.accessOf("sales").roles();

    // Assert
    assertThat(hr).containsExactly("ceo", "hr", "employee", "CEO");
    assertThat(sales).containsExactly("ceo", "hr");
  }

  static Stream<Arguments> lookupsOfUnknownNames() {
    Function<Configuration, Object> spaceOf = configuration -> configuration.spaceOf("nowhere");
    Function<Configuration, Object> airlockOf =
        configuration -> configuration.airlockOf("nowhere-v1");
    // The other lookups go through these two
    return Stream.of(
        arguments(named("spaceOf", spaceOf), "no Airlock nowhere"),
        arguments(named("airlockOf", airlockOf), "no AirlockVersion nowhere-v1"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lookupsOfUnknownNames")
  void givenUnknownName_whenLookedUp_thenRefusedNamingIt(
      Function<Configuration, Object> lookup, String message) {
    // Arrange
    Configuration configuration = buildConfiguration(level -> List.of(), level -> List.of());

    // Act
    Throwable thrown = catchThrowable(() -> lookup.apply(configuration));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
  }

  /** One space, airlock and version, plus the global level, each given its lists by level. */
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
                    llms.apply("airlock"),
                    dataSources.apply("airlock")))),
        Map.of(
            "hr-assistant-v1",
            new AirlockVersionDocument(
                new Metadata("hr-assistant-v1"),
                new AirlockVersionDocument.Spec(
                    "hr-assistant",
                    "v1",
                    null,
                    null,
                    "granite",
                    "Answer.",
                    null,
                    null,
                    llms.apply("version"),
                    null))));
  }

  private static AirlockDocument buildAirlock(String name, AirlockDocument.Access access) {
    return new AirlockDocument(
        new Metadata(name), new AirlockDocument.Spec("hr-acme", null, null, access, null, null));
  }

  private static AirlockVersionDocument buildVersion(String airlock, String version) {
    return new AirlockVersionDocument(
        new Metadata(airlock + "-" + version),
        new AirlockVersionDocument.Spec(
            airlock, version, null, null, "granite", "Answer.", null, null, null, null));
  }

  private static Llm llm(String id, String owner) {
    return new Llm(id, "ollama", "http://" + owner + "-llm:11434", owner + "-model");
  }

  private static DataSource dataSource(String id, String owner) {
    return new DataSource(id, "jdbc:postgresql://" + owner + "-db:5432/hr", owner + "-ds-" + id);
  }
}
