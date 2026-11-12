package com.reportix.aisolate.airlock.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.reportix.aisolate.airlock.support.Documents;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DiscoveredAirlock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DocumentsAirlockCatalogTest {

  private static final Configuration CONFIGURATION = ConfigurationLoader.load(Documents.DIR);

  /** founders-finance gains a v2 with no code and its own prompt. */
  private static final String V2 =
      """
      apiVersion: aisolate.dev/v1
      kind: AirlockVersion
      metadata: {name: founders-finance-v2}
      spec:
        airlock: founders-finance
        version: v2
        llm: qwen
        prompt: v2
      """;

  @Test
  void givenDocuments_whenFound_thenDefinitionCarriesVersionRevisionSpaceAndTools() {
    // Arrange
    DocumentsAirlockCatalog catalog = new DocumentsAirlockCatalog(CONFIGURATION, null, null);

    // Act
    AirlockDefinition airlock = catalog.findAirlock("hr-assistant", "v1").orElseThrow();

    // Assert: who may ask is the space's roles, the airlock's own and its callers; the model and
    // the space's data source come from the space, the airlock adds its own
    assertThat(airlock.version()).isEqualTo("v1");
    assertThat(airlock.description()).isEqualTo("First version of the HR assistant.");
    assertThat(airlock.revision()).isEqualTo(CONFIGURATION.revision());
    assertThat(catalog.revision()).isEqualTo(CONFIGURATION.revision());
    assertThat(airlock.spaceId()).isEqualTo("hr-acme");
    assertThat(airlock.name()).isEqualTo("Acme HR assistant");
    assertThat(airlock.model()).isEqualTo("ollama");
    assertThat(airlock.tools()).containsExactly("read_records", "aggregate_records");
    assertThat(airlock.dataSources()).containsExactly("acme_hr", "hr-documents");
    assertThat(airlock.access().roles()).containsExactly("ceo", "employee");
    assertThat(airlock.access().callers()).containsExactly("*");
    assertThat(airlock.systemPrompt()).startsWith("You are the Acme HR assistant.");
    assertThat(catalog.listAirlocks())
        .extracting(DiscoveredAirlock::id)
        .containsExactly("hr-assistant", "founders-finance");
  }

  @Test
  void givenTwoVersions_whenFound_thenEachServesUnderItsOwnLabel() {
    // Arrange
    DocumentsAirlockCatalog catalog =
        new DocumentsAirlockCatalog(Documents.parseWith(V2), null, null);

    // Act
    AirlockDefinition first = catalog.findAirlock("founders-finance", "v1").orElseThrow();
    AirlockDefinition second = catalog.findAirlock("founders-finance", "v2").orElseThrow();

    // Assert: both serve at the release revision, listed by label; v2 offers no tool; a label the
    // documents lack does not serve
    assertThat(first.tools()).containsExactly("aggregate_records");
    assertThat(second.tools()).isEmpty();
    assertThat(second.systemPrompt()).isEqualTo("v2");
    assertThat(second.revision()).isEqualTo(first.revision());
    assertThat(catalog.findAirlock("founders-finance", "v3")).isEmpty();
    assertThat(catalog.listAirlocks())
        .flatExtracting(airlock ->
            airlock.versions().stream().map(v -> airlock.id() + "/" + v.version()).toList())
        .containsExactly("hr-assistant/v1", "founders-finance/v1", "founders-finance/v2");
  }

  @Test
  void givenAirlockBeforeItsFirstDeploy_whenFound_thenEmptyWhileListedWithoutVersions() {
    // Arrange: travel-desk has no version yet, so nothing to run
    DocumentsAirlockCatalog catalog =
        new DocumentsAirlockCatalog(
            Documents.parseWith(
                """
                apiVersion: aisolate.dev/v1
                kind: Airlock
                metadata: {name: travel-desk}
                spec:
                  space: hr-acme
                  displayName: Travel desk
                  access: {roles: [employee], callers: ["*"]}
                """),
            null,
            null);

    // Act
    Optional<AirlockDefinition> found = catalog.findAirlock("travel-desk", "v1");

    // Assert: not served, a name the documents lack neither; discovery lists it all the same
    assertThat(found).isEmpty();
    assertThat(catalog.findAirlock("nowhere", "v1")).isEmpty();
    assertThat(catalog.served())
        .extracting(AirlockDefinition::id)
        .containsExactly("hr-assistant", "founders-finance");
    assertThat(catalog.listAirlocks())
        .extracting(DiscoveredAirlock::id, a -> a.versions().size())
        .containsExactly(
            tuple("hr-assistant", 1), tuple("founders-finance", 1), tuple("travel-desk", 0));
  }

  @Test
  void givenPin_whenOtherFound_thenEmptyWhileTheListStaysComplete() {
    // Arrange: the pod of founders-finance v1, in a release where v2 runs beside it
    DocumentsAirlockCatalog catalog =
        new DocumentsAirlockCatalog(Documents.parseWith(V2), "founders-finance", "v1");

    // Act
    Optional<AirlockDefinition> otherAirlock = catalog.findAirlock("hr-assistant", "v1");
    Optional<AirlockDefinition> otherVersion = catalog.findAirlock("founders-finance", "v2");
    Optional<AirlockDefinition> pinned = catalog.findAirlock("founders-finance", "v1");

    // Assert
    assertThat(otherAirlock).isEmpty();
    assertThat(otherVersion).isEmpty();
    assertThat(pinned).isPresent();
    assertThat(catalog.served()).extracting(AirlockDefinition::version).containsExactly("v1");
    assertThat(catalog.listAirlocks()).hasSize(2);
  }

  @ParameterizedTest
  @CsvSource({"nowhere, v1, AISOLATE_AIRLOCK", "hr-assistant, v9, AISOLATE_VERSION"})
  void givenPinNotInDocuments_whenConstructed_thenRefused(
      String airlock, String version, String named) {
    // Arrange: a pod rendered for an airlock, or a version of it, the documents do not hold

    // Act
    Throwable thrown =
        catchThrowable(() -> new DocumentsAirlockCatalog(CONFIGURATION, airlock, version));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(named)
        .hasMessageContaining(airlock);
  }
}
