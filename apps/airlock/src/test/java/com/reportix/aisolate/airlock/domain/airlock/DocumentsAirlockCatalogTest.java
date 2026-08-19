package com.reportix.aisolate.airlock.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.reportix.aisolate.airlock.support.Documents;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DiscoveredAirlock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DocumentsAirlockCatalogTest {

  private static final Configuration CONFIGURATION = ConfigurationLoader.load(Documents.DIR);

  @Test
  void givenDocuments_whenFound_thenDefinitionCarriesRevisionSpaceAndTools() {
    // Arrange
    DocumentsAirlockCatalog catalog = new DocumentsAirlockCatalog(CONFIGURATION, null);

    // Act
    AirlockDefinition airlock = catalog.findAirlock("hr-assistant").orElseThrow();

    // Assert: who may ask is the space's users, the airlock's own and its callers; the model and
    // the space's data source come from the space, the airlock adds its own
    assertThat(airlock.description()).isEqualTo("Answers people, pay and HR document questions.");
    assertThat(airlock.revision()).isEqualTo(CONFIGURATION.revision());
    assertThat(catalog.revision()).isEqualTo(CONFIGURATION.revision());
    assertThat(airlock.spaceId()).isEqualTo("hr-acme");
    assertThat(airlock.name()).isEqualTo("Acme HR assistant");
    assertThat(airlock.model()).isEqualTo("ollama");
    assertThat(airlock.tools()).containsExactly("read_records", "aggregate_records");
    assertThat(airlock.dataSources()).containsExactly("acme_hr", "hr-documents");
    assertThat(airlock.access().users()).containsExactly("nora@example.com", "eric@example.com");
    assertThat(airlock.access().callers()).containsExactly("*");
    assertThat(airlock.systemPrompt()).startsWith("You are the Acme HR assistant.");
    assertThat(catalog.listAirlocks())
        .extracting(DiscoveredAirlock::id)
        .containsExactly("hr-assistant", "founders-finance");
  }

  @Test
  void givenAirlockBeforeItsFirstDeploy_whenFound_thenEmptyWhileListedWithoutTools() {
    // Arrange: travel-desk has no llm and no prompt yet, so nothing to run
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
                  access: {users: [eric@example.com], callers: ["*"]}
                """),
            null);

    // Act
    Optional<AirlockDefinition> found = catalog.findAirlock("travel-desk");

    // Assert: not served, a name the documents lack neither; discovery lists it all the same
    assertThat(found).isEmpty();
    assertThat(catalog.findAirlock("nowhere")).isEmpty();
    assertThat(catalog.served())
        .extracting(AirlockDefinition::id)
        .containsExactly("hr-assistant", "founders-finance");
    assertThat(catalog.listAirlocks())
        .extracting(DiscoveredAirlock::id, DiscoveredAirlock::tools)
        .containsExactly(
            tuple("hr-assistant", List.of("read_records", "aggregate_records")),
            tuple("founders-finance", List.of("aggregate_records")),
            tuple("travel-desk", List.of()));
  }

  @Test
  void givenPin_whenOtherFound_thenEmptyWhileTheListStaysComplete() {
    // Arrange
    DocumentsAirlockCatalog catalog = new DocumentsAirlockCatalog(CONFIGURATION, "hr-assistant");

    // Act
    Optional<AirlockDefinition> other = catalog.findAirlock("founders-finance");
    Optional<AirlockDefinition> pinned = catalog.findAirlock("hr-assistant");

    // Assert
    assertThat(other).isEmpty();
    assertThat(pinned).isPresent();
    assertThat(catalog.listAirlocks()).hasSize(2);
  }

  @Test
  void givenPinNotInDocuments_whenConstructed_thenRefused() {
    // Arrange
    String pin = "nowhere";

    // Act
    Throwable thrown = catchThrowable(() -> new DocumentsAirlockCatalog(CONFIGURATION, pin));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("nowhere");
  }
}
