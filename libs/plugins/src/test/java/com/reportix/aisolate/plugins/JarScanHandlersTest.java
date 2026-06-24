package com.reportix.aisolate.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.reportix.aisolate.plugins.HandlerCandidate.Stage;
import com.reportix.aisolate.plugins.testing.HandlerJars;
import com.reportix.aisolate.plugins.testing.PluginJars;
import com.reportix.aisolate.sdk.AnswerVerifier;
import com.reportix.aisolate.sdk.ContextPreparator;
import com.reportix.aisolate.sdk.ConversationMemory;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JarScanHandlersTest {

  @Test
  void givenJarWithABrokenToolPluginAndAHandler_whenLoad_thenTheHandlerLoadsAndNoPluginIsCreated()
      throws IOException {
    // Arrange: a plugin whose constructor needs the tool server's environment
    byte[] jar = HandlerJars.jarOf(PluginJars.BROKEN_TOOLS, HandlerJars.HANDBOOK);

    // Act
    List<HandlerCandidate> found = JarScan.handlersOf(List.of(HandlerJars.writeJar(jar)));

    // Assert
    assertThat(found).extracting(HandlerCandidate::name).containsExactly("HandbookPreparator");
  }

  @Test
  void givenJarWithThreeHandlers_whenLoad_thenEachBecomesACandidateWithItsStageAndOrder()
      throws IOException {
    // Arrange
    byte[] jar =
        HandlerJars.jarOf(HandlerJars.HANDBOOK, HandlerJars.STRICT_VERIFIER, HandlerJars.MEMORY);

    // Act
    List<HandlerCandidate> found = JarScan.handlersOf(List.of(HandlerJars.writeJar(jar)));

    // Assert
    assertThat(found)
        .extracting(HandlerCandidate::name, HandlerCandidate::stage, HandlerCandidate::order)
        .containsExactlyInAnyOrder(
            tuple("HandbookPreparator", Stage.CONTEXT_PREP, 2),
            tuple("StrictVerifier", Stage.VERIFIER, 1),
            tuple("SessionMemory", Stage.MEMORY, 1));
    assertThat(found)
        .filteredOn(candidate -> candidate.name().equals("HandbookPreparator"))
        .first()
        .extracting(HandlerCandidate::instance)
        .isInstanceOf(ContextPreparator.class);
    assertThat(found)
        .filteredOn(candidate -> candidate.name().equals("StrictVerifier"))
        .first()
        .extracting(HandlerCandidate::instance)
        .isInstanceOf(AnswerVerifier.class);
  }

  @Test
  void givenClassThatCannotLink_whenLoad_thenSkipped() throws IOException {
    // Arrange: a class extending one the jar does not carry, next to a good handler
    byte[] jar =
        PluginJars.buildJar(
            merge(
                PluginJars.entriesOf(HandlerJars.unlinkableJar()),
                PluginJars.entriesOf(HandlerJars.jarOf(HandlerJars.HANDBOOK))));

    // Act
    List<HandlerCandidate> found = JarScan.handlersOf(List.of(HandlerJars.writeJar(jar)));

    // Assert
    assertThat(found).extracting(HandlerCandidate::name).containsExactly("HandbookPreparator");
  }

  @Test
  void givenClassCompiledForANewerJava_whenLoad_thenRefusedNamingTheClass() {
    // Arrange: the handler's class file claims major version 99
    String classFile = "com/example/hr/HandbookPreparator.class";
    Map<String, byte[]> entries = PluginJars.entriesOf(HandlerJars.jarOf(HandlerJars.HANDBOOK));
    byte[] bytes = entries.get(classFile).clone();
    bytes[6] = 0;
    bytes[7] = 99;
    entries.put(classFile, bytes);
    byte[] jar = PluginJars.buildJar(entries);

    // Act
    Throwable thrown = catchThrowable(() -> JarScan.handlersOf(List.of(HandlerJars.writeJar(jar))));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("com.example.hr.HandbookPreparator")
        .hasMessageContaining("newer Java");
  }

  @Test
  void givenInitializerErrorWithoutCause_whenLoad_thenRefusedWithItsMessage() {
    // Arrange: an ExceptionInInitializerError thrown by hand has a message and no cause
    byte[] jar =
        HandlerJars.jarOf(
            new PluginJars.Plugin(
                "com.example.broken.UnconfiguredPreparator",
                """
                package com.example.broken;

                import com.reportix.aisolate.sdk.*;

                public class UnconfiguredPreparator implements ContextPreparator {
                  static {
                    if (true) {
                      throw new ExceptionInInitializerError("no index configured");
                    }
                  }

                  public void prepare(Exchange exchange, AIsolateManager aisolate) {}
                }
                """));

    // Act
    Throwable thrown = catchThrowable(() -> JarScan.handlersOf(List.of(HandlerJars.writeJar(jar))));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("com.example.broken.UnconfiguredPreparator")
        .hasMessageContaining("no index configured");
  }

  @Test
  void givenJarBundlingTheSdkStages_whenLoad_thenTheyAreNotHandlers() throws IOException {
    // Arrange: a fat jar carries the SDK's own stage interfaces next to a real handler
    byte[] jar =
        PluginJars.buildJar(
            merge(
                PluginJars.entriesOf(HandlerJars.jarOf(HandlerJars.HANDBOOK)), sdkStageEntries()));

    // Act
    List<HandlerCandidate> found = JarScan.handlersOf(List.of(HandlerJars.writeJar(jar)));

    // Assert
    assertThat(found).extracting(HandlerCandidate::name).containsExactly("HandbookPreparator");
  }

  private static Map<String, byte[]> merge(Map<String, byte[]> a, Map<String, byte[]> b) {
    Map<String, byte[]> all = new LinkedHashMap<>(a);
    all.putAll(b);
    return all;
  }

  /** The class files of the SDK's stage interfaces, as a fat jar bundles them. */
  private static Map<String, byte[]> sdkStageEntries() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    for (Class<?> type :
        List.of(ContextPreparator.class, ConversationMemory.class, AnswerVerifier.class)) {
      try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
        entries.put(type.getName().replace('.', '/') + ".class", in.readAllBytes());
      }
    }
    return entries;
  }
}
