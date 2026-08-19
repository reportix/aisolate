package com.reportix.aisolate.airlock.infrastructure.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.airlock.domain.airlock.AirlockCatalog;
import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition.HandlerSelection;
import com.reportix.aisolate.airlock.domain.chat.HandlerCatalog;
import com.reportix.aisolate.airlock.domain.chat.Handlers;
import com.reportix.aisolate.airlock.support.TestAirlocks;
import com.reportix.aisolate.config.DiscoveredAirlock;
import com.reportix.aisolate.plugins.JarDigests;
import com.reportix.aisolate.plugins.testing.HandlerJars;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pod's handlers come from the pinned directory and nowhere else; JarDigests pins it. A served
 * airlock listing a handler its jar does not carry fails the start, not the first chat.
 */
class PluginsConfigTest {

  private static final byte[] DIR_JAR = HandlerJars.jarOf(HandlerJars.HANDBOOK);

  /** Its digest in upper case, as a hand-written document may carry it; the pin ignores case. */
  private static final HandlerSelection HANDBOOK =
      new HandlerSelection(
          JarDigests.of(DIR_JAR).toUpperCase(Locale.ROOT),
          List.of("HandbookPreparator"),
          List.of(),
          List.of());

  private static final HandlerSelection MISSING =
      new HandlerSelection(JarDigests.of(DIR_JAR), List.of("Missing"), List.of(), List.of());

  /**
   * What the pod serves: the one airlock given, or none. Discovery does not list it yet, as when a
   * new airlock's pod starts before the controller's next pass.
   */
  private record Served(List<AirlockDefinition> airlocks) implements AirlockCatalog {
    @Override
    public Optional<AirlockDefinition> findAirlock(String id) {
      return airlocks.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    @Override
    public List<DiscoveredAirlock> listAirlocks() {
      return List.of();
    }

    @Override
    public List<AirlockDefinition> served() {
      return airlocks;
    }

    @Override
    public String revision() {
      return "1";
    }
  }

  @TempDir Path dir;

  private static AirlockDefinition airlock(HandlerSelection handlers) {
    return TestAirlocks.acmeHr("1", "ollama", List.of("read_records"), handlers);
  }

  private static Handlers load(HandlerCatalog catalog, HandlerSelection handlers) {
    return catalog.resolveHandlers(airlock(handlers));
  }

  private HandlerCatalog catalog(String digests, HandlerSelection served) throws Exception {
    return new PluginsConfig()
        .handlerCatalog(dir.toString(), digests, "", "", "", new Served(List.of(airlock(served))));
  }

  @Test
  void givenPinnedJar_whenHandlersFor_thenItsListedHandlerLoads() throws Exception {
    // Arrange
    Files.write(dir.resolve("hr.jar"), DIR_JAR);
    // The controller pins the document's digest as written.
    HandlerCatalog catalog = catalog(HANDBOOK.jarDigest(), HANDBOOK);

    // Act
    Handlers handlers = load(catalog, HANDBOOK);

    // Assert
    assertThat(handlers.contextPreparations()).hasSize(1);
    assertThat(handlers.memories()).isEmpty();
  }

  @Test
  void givenNoDirectory_whenHandlersFor_thenNoHandlers() throws Exception {
    // Arrange: an airlock without code
    HandlerCatalog catalog =
        new PluginsConfig()
            .handlerCatalog(
                "", "", "", "", "", new Served(List.of(airlock(TestAirlocks.NO_HANDLERS))));

    // Act
    Handlers handlers = load(catalog, TestAirlocks.NO_HANDLERS);

    // Assert
    assertThat(handlers).isEqualTo(new Handlers(List.of(), List.of(), List.of()));
  }

  @Test
  void givenServedAirlockListingAHandlerNoClassCarries_whenStart_thenFailsNamingIt()
      throws Exception {
    // Arrange
    Files.write(dir.resolve("hr.jar"), DIR_JAR);

    // Act
    Throwable thrown = catchThrowable(() -> catalog(JarDigests.of(DIR_JAR), MISSING));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no context preparator Missing in the code of hr-acme/acme-hr");
  }
}
