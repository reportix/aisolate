package com.reportix.aisolate.airlock.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.AirlockApplication;
import com.reportix.aisolate.airlock.domain.chat.McpToolClient;
import com.reportix.aisolate.airlock.support.AirlockTestConfig;
import com.reportix.aisolate.airlock.support.FakeToolClient;
import com.reportix.aisolate.airlock.support.ScriptedChatModel;
import com.reportix.aisolate.identity.testing.Tokens;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.observation.ChatClientCompletionObservationHandler;
import org.springframework.ai.chat.client.observation.ChatClientPromptContentObservationHandler;
import org.springframework.ai.chat.observation.ChatModelCompletionObservationHandler;
import org.springframework.ai.chat.observation.ChatModelPromptContentObservationHandler;
import org.springframework.ai.tool.observation.ToolCallingContentObservationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ContextConfiguration;

/**
 * What the browser sees while an exchange runs: events arrive as they happen, on the request's
 * thread.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = AirlockApplication.class)
@ContextConfiguration(
    initializers = {AirlockTestConfig.class, ChatEndpointSecurityTest.Documents.class})
// ChatEndpointSecurityTest's stubs and documents: one cached Spring context for both classes
@Import(ChatEndpointSecurityTest.Stubs.class)
class ChatStreamingIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private ScriptedChatModel model;
  @Autowired private McpToolClient toolServer;
  @Autowired private ApplicationContext context;

  private final HttpClient client = HttpClient.newHttpClient();
  private final CountDownLatch releaseHeldExchange = new CountDownLatch(1);

  @BeforeEach
  void resetScript() {
    model.reset();
  }

  @AfterEach
  void releaseEverything() {
    releaseHeldExchange.countDown();
    // close() would block until every half-read stream is drained.
    client.shutdownNow();
  }

  @Test
  void givenToolCall_whenStreaming_thenToolAndEvidenceArriveBeforeTheAnswerIsAsked()
      throws Exception {
    // Arrange: the model is held inside its second call, after the tool has already run
    CountDownLatch answerAsked = new CountDownLatch(1);
    model.toolCall("read_records", "{}").answerCiting("{f1}", "ev-1", "r1", "gross_salary");
    model.onCall(
        () -> {
          if (model.prompts().size() == 2) {
            answerAsked.countDown();
            awaitLatch(releaseHeldExchange);
          }
        });

    // Act
    Iterator<String> lines = chatLines("eric");
    String toolCall = nextDataLine(lines);
    String evidence = nextDataLine(lines);

    // Assert: both frames are on the wire while the model is still being asked for the answer
    assertThat(answerAsked.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(toolCall).contains("\"type\":\"tool_call\"");
    assertThat(evidence).contains("\"type\":\"evidence\"").doesNotContain("Eric Lindholm");
    releaseHeldExchange.countDown();
    assertThat(remainingDataLines(lines)).anyMatch(line -> line.contains("\"type\":\"done\""));
  }

  /**
   * The real MCP client reads the user's Jwt from the calling thread's security context; the fake
   * stands in its place and records what that thread held. If the exchange or the tool execution
   * ever moves off the request thread, this is the test that goes red.
   */
  @Test
  void givenAuthenticatedRequest_whenToolRuns_thenTheToolClientSeesTheRequestsJwtOnItsThread() {
    // Arrange
    model.toolCall("read_records", "{}").answerCiting("{f1}", "ev-1", "r1", "gross_salary");

    // Act
    Iterator<String> lines = chatLines("eric");

    // Assert
    assertThat(remainingDataLines(lines)).anyMatch(line -> line.contains("\"type\":\"done\""));
    assertThat(((FakeToolClient) toolServer).callerAuthentications())
        .isNotEmpty()
        .allSatisfy(
            authentication ->
                assertThat(authentication)
                    .isNotNull()
                    .extracting(Authentication::getPrincipal)
                    .isInstanceOf(Jwt.class));
  }

  @Test
  void
      givenTheApplicationContext_whenInspected_thenNothingRecordsPromptsCompletionsOrToolContent() {
    // Arrange: what would put the prompt, which from round two holds the evidence rows, into a span
    List<Class<?>> recorders =
        List.of(
            ChatModelPromptContentObservationHandler.class,
            ChatModelCompletionObservationHandler.class,
            ChatClientPromptContentObservationHandler.class,
            ChatClientCompletionObservationHandler.class,
            ToolCallingContentObservationFilter.class);
    List<String> switches =
        List.of(
            "spring.ai.chat.observations.log-prompt",
            "spring.ai.chat.observations.log-completion",
            "spring.ai.chat.client.observations.log-prompt",
            "spring.ai.chat.client.observations.log-completion",
            "spring.ai.tools.observations.include-content");

    // Act
    List<String> present =
        recorders.stream().flatMap(type -> Stream.of(context.getBeanNamesForType(type))).toList();
    List<Boolean> on =
        switches.stream()
            .map(key -> context.getEnvironment().getProperty(key, Boolean.class, true))
            .toList();

    // Assert
    assertThat(present).isEmpty();
    assertThat(on).containsOnly(false);
  }

  private HttpRequest chatRequest(String user) {
    return HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/v1/spaces/hr-acme/airlocks/acme-hr/chat"))
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream")
        .header("Authorization", "Bearer " + Tokens.forUser(user, "password"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"salary?\"}"))
        .build();
  }

  /** Reads the stream line by line: {@code send} returns as soon as the headers are flushed. */
  private Iterator<String> chatLines(String user) {
    try {
      HttpResponse<Stream<String>> response =
          client.send(chatRequest(user), HttpResponse.BodyHandlers.ofLines());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("Content-Type"))
          .hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
      return response.body().iterator();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String nextDataLine(Iterator<String> lines) {
    while (lines.hasNext()) {
      String line = lines.next();
      if (line.startsWith("data:")) {
        return line;
      }
    }
    throw new AssertionError("the stream ended before the next event");
  }

  private static Stream<String> remainingDataLines(Iterator<String> lines) {
    return Stream.generate(() -> lines.hasNext() ? lines.next() : null)
        .takeWhile(line -> line != null)
        .filter(line -> line.startsWith("data:"));
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
