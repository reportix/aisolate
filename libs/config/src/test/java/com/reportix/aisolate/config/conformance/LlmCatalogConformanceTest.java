package com.reportix.aisolate.config.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationException;
import com.reportix.aisolate.config.ConfigurationLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code conformance/cases/llm/catalog.json}: the LLMs a version sees over the four levels, the
 * data sources over three, its main LLM, and the loader's refusals; shared with the Python loader.
 */
class LlmCatalogConformanceTest {

  // A case writes no secretRef for an LLM without one.
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .changeDefaultPropertyInclusion(
              inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
          .build();
  private static final JsonNode FILE = read();

  @TempDir Path documents;

  static Stream<Arguments> cases() {
    return StreamSupport.stream(FILE.path("cases").spliterator(), false)
        .map(c -> arguments(named(c.path("id").asString(), c)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenDocumentsOverFourLevels_whenLoaded_thenTheVersionsCatalogOrTheProblems(JsonNode c)
      throws IOException {
    // Arrange
    Map<String, JsonNode> files = new LinkedHashMap<>();
    FILE.path("base").properties().forEach(f -> files.put(f.getKey(), f.getValue()));
    c.path("files")
        .properties()
        .forEach(f -> {
          if (f.getValue().isNull()) {
            files.remove(f.getKey());
          } else {
            files.put(f.getKey(), f.getValue());
          }
        });
    for (Map.Entry<String, JsonNode> file : files.entrySet()) {
      Files.writeString(documents.resolve(file.getKey()), JSON.writeValueAsString(file.getValue()));
    }
    Configuration[] loaded = new Configuration[1];

    // Act
    Throwable thrown = catchThrowable(() -> loaded[0] = ConfigurationLoader.load(documents));

    // Assert
    JsonNode expect = c.path("expect");
    if (expect.has("problems")) {
      assertThat(thrown).isInstanceOf(ConfigurationException.class);
      assertThat(((ConfigurationException) thrown).problems())
          .containsExactlyInAnyOrderElementsOf(
              expect.path("problems").valueStream().map(JsonNode::asString).toList());
    } else {
      assertThat(thrown).isNull();
      Configuration configuration = loaded[0];
      assertThat((JsonNode) JSON.valueToTree(configuration.llmsOf("hr-v1")))
          .isEqualTo(expect.path("llms"));
      assertThat(configuration.mainLlmOf("hr-v1").id()).isEqualTo(expect.path("main").asString());
      assertThat((JsonNode) JSON.valueToTree(configuration.dataSourcesOf("hr-v1")))
          .isEqualTo(expect.path("data_sources"));
    }
  }

  private static JsonNode read() {
    String dir = System.getProperty("aisolate.conformance.dir");
    if (dir == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    return JSON.readTree(Path.of(dir, "llm", "catalog.json").toFile());
  }
}
