package com.reportix.aisolate.airlock.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.AirlockApplication;
import com.reportix.aisolate.airlock.domain.chat.McpToolClient;
import com.reportix.aisolate.airlock.support.AirlockTestConfig;
import com.reportix.aisolate.airlock.support.FakeToolClient;
import com.reportix.aisolate.airlock.support.ScriptedChatModel;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Tokens;
import com.reportix.aisolate.plugins.testing.HandlerJars;
import com.reportix.aisolate.plugins.testing.PluginJars;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The developer's code as the pod runs it: two airlocks, each with its own jar mounted in the
 * plugins directory and pinned by digest. The pod serves both here (no pin), so one context shows
 * that an airlock runs the handlers of its own jar only, even where the other jar carries a handler
 * of the same name.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = AirlockApplication.class)
@ContextConfiguration(
    initializers = {AirlockTestConfig.class, HandlerChatIntegrationTest.Release.class})
@Import(HandlerChatIntegrationTest.Stubs.class)
@ExtendWith(OutputCaptureExtension.class)
class HandlerChatIntegrationTest {

  private static final String AIRLOCK = "handler-lab-assistant";
  private static final String OTHER_AIRLOCK = "other-assistant";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final PluginJars.Plugin CITATION_VERIFIER =
      new PluginJars.Plugin(
          "com.example.hr.CitationVerifier",
          """
          package com.example.hr;
          import com.reportix.aisolate.sdk.*;
          import java.util.List;
          public class CitationVerifier implements AnswerVerifier {
            @Override
            public int order() {
              return 1;
            }

            public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager ll) {
              return answer.text().contains("[ev-") ? Verdict.pass("cited") : Verdict.warn("no citation");
            }
          }
          """);

  /** The other airlock's verifier: the same simple name, another package, another verdict. */
  private static final PluginJars.Plugin OTHER_CITATION_VERIFIER =
      new PluginJars.Plugin(
          "com.example.other.CitationVerifier",
          """
          package com.example.other;
          import com.reportix.aisolate.sdk.*;
          import java.util.List;
          public class CitationVerifier implements AnswerVerifier {
            @Override
            public int order() {
              return 1;
            }

            public Verdict verify(Answer answer, List<Evidence> evidence, AIsolateManager ll) {
              return Verdict.fail("the other airlock's code");
            }
          }
          """);

  /** This airlock's tools plus the three handlers, one jar. */
  private static final byte[] JAR =
      HandlerJars.jarOf(
          PluginJars.HR_TOOLS, HandlerJars.HANDBOOK, HandlerJars.MEMORY, CITATION_VERIFIER);

  /** The other airlock's jar: its own verifier, no tools. */
  private static final byte[] OTHER_JAR = HandlerJars.jarOf(OTHER_CITATION_VERIFIER);

  /** What runs before the model on the handler lab assistant; the other assistant has none. */
  private static final String PRE_LLM =
      """
      preLLM:
        contextPreparator:
          handlers: [HandbookPreparator]
        conversationMemory:
          handlers: [SessionMemory]
      """;

  /** The release as the controller mounts it: documents in one directory, jars in another. */
  static final Path RELEASE = writeRelease();

  static final class Release
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      TestPropertyValues.of(
              "aisolate.config.dir=" + RELEASE.resolve("documents"),
              "aisolate.plugins.dir=" + RELEASE.resolve("plugins"),
              "aisolate.plugins.digests=" + sha256(JAR) + "," + sha256(OTHER_JAR),
              "aisolate.tool-server.url=http://handler-lab-handler-lab-assistant-mcp-server:8081/mcp")
          .applyTo(context);
    }
  }

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    ScriptedChatModel scriptedChatModel() {
      return new ScriptedChatModel();
    }

    @Bean
    @Primary
    McpToolClient fakeToolClient() {
      return new FakeToolClient()
          .tool("read_records", args -> FakeToolClient.evidence(1, "own-record"));
    }
  }

  @LocalServerPort int port;
  @Autowired ScriptedChatModel model;

  private final String eric = Tokens.forUser("eric", "password");

  @BeforeEach
  void resetModel() {
    model.reset();
  }

  @Test
  void givenMountedJarWithHandlers_whenChat_thenPassageEvidenceVerificationAndMemoryRun(
      CapturedOutput output) {
    // Arrange: two scripted answers, one per exchange
    model.answer("Three years [ev-1].").answer("You asked about leave; the answer stands [ev-1].");

    // Act: two exchanges as Eric
    List<String> first =
        frames(chat(AIRLOCK, eric, "{\"message\":\"How long is parental leave?\"}"));
    List<String> second =
        frames(chat(AIRLOCK, eric, "{\"message\":\"And what did I ask before?\"}"));

    // Assert: the handler's passage came first as evidence without its text, the verifier spoke,
    // and the second exchange's prompt carried the memory
    assertThat(first.getFirst())
        .contains("\"type\":\"evidence\"")
        .contains("\"tool\":\"hr-documents\"")
        .doesNotContain("three years of parental leave");
    assertThat(first).anySatisfy(frame -> assertThat(frame).contains("\"type\":\"answer_chunk\""));
    assertThat(first)
        .anySatisfy(
            frame ->
                assertThat(frame)
                    .contains("\"type\":\"verification\"")
                    .contains("\"handler\":\"CitationVerifier\"")
                    .contains("\"status\":\"PASS\""));
    assertThat(first.getLast()).contains("\"type\":\"done\"");
    assertThat(model.prompts().get(1).getContents())
        .contains("The user's previous question was: How long is parental leave?");
    assertThat(second).anySatisfy(frame -> assertThat(frame).contains("\"type\":\"verification\""));
    // The pod's log says what ran: the operator reads it, the browser never sees it.
    assertThat(output.getOut())
        .contains("exchange_start airlock=handler-lab/" + AIRLOCK)
        .contains("handlers_resolved airlock=handler-lab/" + AIRLOCK)
        .contains("handler_ran stage=context_prep")
        .contains("verifier_ran name=CitationVerifier status=PASS")
        .contains("handler_ran stage=memory_remember")
        .contains("exchange_done airlock=handler-lab/" + AIRLOCK);
  }

  @Test
  void givenJsonAccepted_whenChat_thenTheAnswerEvidenceAndVerdictsInOneDocument() {
    // Arrange
    model.answer("Three years [ev-1].");

    // Act
    HttpResponse<String> response =
        chat(
            AIRLOCK,
            eric,
            "application/json",
            "{\"message\":\"How many years of parental leave, as one document?\"}");

    // Assert
    assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
    JsonNode answer = JSON.readTree(response.body());
    // The whole document, as docs/api.md lists it
    assertThat(new ArrayList<>(answer.propertyNames()))
        .containsExactly(
            "answer",
            "dont_know",
            "segments",
            "figures",
            "tables",
            "quotes",
            "provenance",
            "evidence",
            "tool_errors",
            "verification",
            "revision",
            "trace_id");
    assertThat(answer.path("tool_errors")).isEmpty();
    assertThat(answer.path("answer").asString()).isEqualTo("Three years [ev-1].");
    assertThat(answer.path("dont_know").asBoolean()).isFalse();
    JsonNode evidence = answer.path("evidence");
    assertThat(evidence).hasSize(1);
    assertThat(evidence.get(0).path("id").asString()).isEqualTo("ev-1");
    assertThat(evidence.get(0).path("source").asString()).isEqualTo("hr-documents");
    assertThat(evidence.get(0).path("name").asString()).isEqualTo("hr-documents");
    assertThat(evidence.get(0).path("rows").asInt()).isEqualTo(1);
    assertThat(response.body()).doesNotContain("three years of parental leave");
    assertThat(answer.path("verification").path("verified").asBoolean()).isTrue();
    JsonNode verdicts = answer.path("verification").path("verdicts");
    assertThat(verdicts).hasSize(1);
    assertThat(verdicts.get(0).path("name").asString()).isEqualTo("CitationVerifier");
    assertThat(verdicts.get(0).path("verdict").asString()).isEqualTo("PASS");
    assertThat(answer.path("revision").asString()).isNotBlank();
    assertThat(answer.path("trace_id").asString()).matches("[0-9a-f]{32}");
  }

  @Test
  void givenHandlerOfAnotherAirlocksJar_whenChat_thenItDoesNotRun() {
    // Arrange: both airlocks list a CitationVerifier, each jar carries its own
    model.answer("Three years [ev-1].").answer("Ask HR [ev-1].");

    // Act: another question, or its leftover memory could satisfy the memory test's assertion
    List<String> exchange =
        frames(chat(AIRLOCK, eric, "{\"message\":\"Who approves parental leave?\"}"));
    List<String> other =
        frames(chat(OTHER_AIRLOCK, eric, "{\"message\":\"Who approves parental leave?\"}"));

    // Assert: each airlock heard its own jar's verifier, never the other's
    assertThat(exchange)
        .anySatisfy(
            frame ->
                assertThat(frame)
                    .contains("\"type\":\"verification\"")
                    .contains("\"status\":\"PASS\"")
                    .doesNotContain("the other airlock's code"));
    assertThat(other)
        .anySatisfy(
            frame ->
                assertThat(frame)
                    .contains("\"type\":\"verification\"")
                    .contains("\"status\":\"FAIL\"")
                    .contains("the other airlock's code"));
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path writeRelease() {
    try {
      Path root =
          Files.createTempDirectory(Files.createDirectories(Path.of("build")), "handler-lab-");
      Path documents = Files.createDirectories(root.resolve("documents"));
      Path plugins = Files.createDirectories(root.resolve("plugins"));
      Files.write(plugins.resolve(AIRLOCK + ".jar"), JAR);
      Files.write(plugins.resolve(OTHER_AIRLOCK + ".jar"), OTHER_JAR);
      Files.writeString(
          documents.resolve("space.yaml"),
          """
          apiVersion: aisolate.dev/v1
          kind: Space
          metadata: {name: handler-lab}
          spec:
            displayName: Handler lab
            access: {users: [eric@example.com]}
            llms: [{id: scripted, provider: ollama, url: http://host.minikube.internal:11434, model: scripted}]
          """);
      Files.writeString(
          documents.resolve("airlocks.yaml"),
          String.join(
              "---\n",
              airlock(
                  AIRLOCK,
                  "Handler lab assistant",
                  PRE_LLM,
                  "read_records, aggregate_records",
                  sha256(JAR)),
              airlock(OTHER_AIRLOCK, "Other assistant", "", "", sha256(OTHER_JAR))));
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** One airlock in pipeline order; {@code preLLM} is a whole block or nothing. */
  private static String airlock(
      String name, String displayName, String preLLM, String tools, String digest) {
    return """
    apiVersion: aisolate.dev/v1
    kind: Airlock
    metadata: {name: %s}
    spec:
      space: handler-lab
      displayName: %s
      access: {users: [eric@example.com], callers: [acme-chat]}
    %s  llm: scripted
      prompt: You are the handler lab assistant.
      tools: [%s]
      postLLM:
        answerVerifier:
          handlers: [CitationVerifier]
      jar: {digest: "%s", tools: [%s]}
    """
        .formatted(name, displayName, preLLM.indent(2), tools, digest, tools);
  }

  private HttpResponse<String> chat(String airlock, String bearer, String body) {
    return chat(airlock, bearer, "text/event-stream", body);
  }

  private HttpResponse<String> chat(String airlock, String bearer, String accept, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/v1/spaces/handler-lab/airlocks/"
                        + airlock
                        + "/chat"))
            .header("Content-Type", "application/json")
            .header("Accept", accept)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    return Http.send(Http.bearer(request, bearer).build());
  }

  /** The {@code data:} lines of an SSE body, in order. */
  private static List<String> frames(HttpResponse<String> response) {
    assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(200);
    return Arrays.stream(response.body().split("\n"))
        .filter(line -> line.startsWith("data:"))
        .map(line -> line.substring(5).strip())
        .toList();
  }
}
