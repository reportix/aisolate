package com.reportix.aisolate.toolserver.conformance;

import static com.reportix.aisolate.toolserver.conformance.ConformanceCases.JSON;
import static com.reportix.aisolate.toolserver.conformance.ConformanceCases.SNAKE;
import static com.reportix.aisolate.toolserver.conformance.ConformanceCases.canonical;
import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.identity.IdentityProperties;
import com.reportix.aisolate.identity.Principal;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import com.reportix.aisolate.toolserver.domain.CustomerDataSources;
import com.reportix.aisolate.toolserver.domain.GovernedQueryService;
import com.reportix.aisolate.toolserver.domain.PluginCatalog;
import com.reportix.aisolate.toolserver.domain.PolicySource;
import com.reportix.aisolate.toolserver.domain.UserToken;
import com.reportix.aisolate.toolserver.domain.rules.RulesEvaluator;
import com.reportix.aisolate.toolserver.domain.sql.SqlCompiler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code conformance/cases/admission/tool-server.json}: gate 5 before any lookup or query, through
 * {@code GovernedQueryService.call} with the served policies and plugins publishing the tools. The
 * plugins declare no entity and the server has no data source, so a refused call that reached any
 * lookup would fail instead of answering the empty result.
 */
class ToolServerAdmissionConformanceTest {

  private static final JsonNode FILE = ConformanceCases.file("admission", "tool-server.json");

  static Stream<Arguments> cases() {
    return ConformanceCases.cases(FILE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @SuppressWarnings("unchecked")
  void givenTokenMetaAndTool_whenCalled_thenAdmittedOrTheSilentEmptyResultAndOneDeniedCount(
      JsonNode testCase) {
    // Arrange
    List<String> reached = new ArrayList<>();
    PluginCatalog catalog = new PluginCatalog(plugins(reached), List.of());
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    GovernedQueryService service =
        new GovernedQueryService(
            new PolicySource(configuration()),
            new CustomerDataSources(Map.of()),
            new RulesEvaluator(),
            new SqlCompiler(),
            catalog,
            ObservationRegistry.NOOP,
            meters,
            new IdentityProperties(null, Principal.DEFAULT_ROLES_CLAIM, null));
    Map<String, Object> claims =
        (Map<String, Object>) ConformanceCases.value(testCase.path("claims"));
    JsonNode meta = testCase.path("meta");
    String tool = testCase.path("tool").asString();
    JsonNode expect = testCase.path("expect");

    // Act
    Result result =
        service.call(
            new UserToken(claims), pin(meta, "airlock"), pin(meta, "revision"), tool, Map.of());

    // Assert
    if (expect.path("admitted").booleanValue()) {
      assertThat(reached).containsExactly(tool);
      return;
    }
    assertThat(reached).isEmpty();
    ObjectNode evidence = SNAKE.valueToTree(result);
    // GovernedToolsConfig.addDataSource: the publishing plugin's, whatever the outcome.
    evidence.put("data_source", catalog.findTool(tool).map(ToolPlugin::dataSource).orElse(null));
    assertThat(canonical(evidence)).isEqualTo(canonical(expect.path("result")));
    Map<String, String> labels = new TreeMap<>();
    JSON.convertValue(expect.path("metric"), Map.class)
        .forEach((k, v) -> labels.put((String) k, (String) v));
    labels.put("tool", tool);
    List<Counter> counters = List.copyOf(meters.find("aisolate.tool.queries").counters());
    assertThat(counters).hasSize(1);
    Map<String, String> tags = new TreeMap<>();
    counters.getFirst().getId().getTags().forEach(t -> tags.put(t.getKey(), t.getValue()));
    assertThat(tags).isEqualTo(labels);
    assertThat(counters.getFirst().count()).isEqualTo(1.0);
  }

  /** {@code GovernedToolsConfig.readPin}: a JSON string, anything else pins nothing. */
  private static String pin(JsonNode meta, String key) {
    return meta.path(key).isString() ? meta.path(key).asString() : null;
  }

  /** One plugin per data source, publishing the tools the case file says it publishes. */
  private static List<ToolPlugin> plugins(List<String> reached) {
    Map<String, List<Tool>> byDataSource = new LinkedHashMap<>();
    FILE.path("published_tools")
        .properties()
        .forEach(
            e -> {
              String name = e.getKey();
              byDataSource
                  .computeIfAbsent(e.getValue().asString(), ds -> new ArrayList<>())
                  .add(
                      new Tool(
                          name,
                          name,
                          Map.of(),
                          (query, args) -> {
                            reached.add(name);
                            return Result.empty(name, "");
                          }));
            });
    return byDataSource.entrySet().stream()
        .map(e -> (ToolPlugin) new Published(e.getKey(), e.getValue()))
        .toList();
  }

  private record Published(String dataSource, List<Tool> tools) implements ToolPlugin {
    @Override
    public String name() {
      return dataSource + "-tools";
    }

    @Override
    public List<Entity> entities() {
      return List.of();
    }
  }

  /** The case file's space and policies as mounted documents, served at its revision. */
  private static Configuration configuration() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put(
        "space.yaml",
        document(
            "Space",
            "conformance",
            Map.of(
                "access",
                Map.of("users", FILE.path("space").path("users")),
                "llms",
                List.of(
                    Map.of(
                        "id", "granite",
                        "provider", "ollama",
                        "url", "http://ollama:11434",
                        "model", "granite4.1:8b")))));
    for (JsonNode policy : FILE.path("policies")) {
      String airlock = policy.path("airlock").asString();
      files.put(
          "airlock-" + airlock + ".yaml",
          document(
              "Airlock",
              airlock,
              Map.of(
                  "space",
                  "conformance",
                  "access",
                  Map.of(
                      "users",
                      policy.path("users"),
                      "callers",
                      policy.path("callers"),
                      "clients",
                      policy.has("clients") ? policy.path("clients") : List.of()),
                  "llm",
                  "granite",
                  "prompt",
                  "conformance",
                  "tools",
                  policy.path("tools"),
                  "jar",
                  Map.of("digest", "sha256:00", "tools", policy.path("tools")))));
    }
    Configuration parsed = ConfigurationLoader.parse(files);
    return new Configuration(
        FILE.path("served_revision").asString(),
        parsed.global(),
        parsed.spaces(),
        parsed.airlocks());
  }

  /** JSON is YAML: one document, as a file of the documents ConfigMap. */
  private static String document(String kind, String name, Map<String, Object> spec) {
    return JSON.writeValueAsString(
        Map.of(
            "apiVersion",
            ConfigurationLoader.API_VERSION,
            "kind",
            kind,
            "metadata",
            Map.of("name", name),
            "spec",
            spec));
  }
}
