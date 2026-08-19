package com.reportix.aisolate.airlock.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.AirlockApplication;
import com.reportix.aisolate.airlock.domain.airlock.AirlockCatalog;
import com.reportix.aisolate.airlock.domain.chat.McpToolClient;
import com.reportix.aisolate.airlock.support.AirlockTestConfig;
import com.reportix.aisolate.airlock.support.Documents;
import com.reportix.aisolate.airlock.support.FakeToolClient;
import com.reportix.aisolate.airlock.support.ScriptedChatModel;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Tokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * The airlock pod as the controller renders it: the airlocks from a directory of documents, pinned
 * to one of them, and no AIsolate UI endpoint. The model and the tool server are the same stubs as
 * in {@link ChatEndpointSecurityTest}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = AirlockApplication.class)
@ContextConfiguration(initializers = {AirlockTestConfig.class, AirlockIntegrationTest.Pod.class})
@Import(ChatEndpointSecurityTest.Stubs.class)
class AirlockIntegrationTest {

  /** The env declares the revision the mounted documents hash to, as the controller renders it. */
  static final String REVISION = ConfigurationLoader.load(Documents.DIR).revision();

  static final class Pod implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      TestPropertyValues.of(
              "aisolate.config.dir=" + Documents.DIR.toAbsolutePath(),
              "aisolate.config.revision=" + REVISION,
              "aisolate.airlock=hr-assistant",
              "aisolate.tool-server.url=http://hr-acme-hr-assistant-mcp-server:8081/mcp")
          .applyTo(context);
    }
  }

  @LocalServerPort int port;
  @Autowired AirlockCatalog catalog;
  @Autowired McpToolClient toolServer;
  @Autowired ScriptedChatModel model;

  private final String nora = Tokens.forUser("nora", "password"); // on both airlocks
  private final String eric = Tokens.forUser("eric", "password"); // on hr-assistant only

  @BeforeEach
  void scriptOneToolCallThenAnAnswer() {
    model.reset();
    model
        .toolCall("read_records", "{}")
        .answerCiting("Your salary is {f1}.", "ev-1", "r1", "gross_salary");
  }

  @Test
  void givenPinnedAirlock_whenChatOtherAirlock_then404() {
    // Arrange: founders-finance admits nora, but this process serves hr-assistant
    String path = "/v1/spaces/hr-acme/airlocks/founders-finance/chat";

    // Act
    HttpResponse<String> response = post(nora, path, "{\"message\":\"hi\"}");

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(model.prompts()).isEmpty();
  }

  @Test
  void givenPinnedAirlock_whenChatIt_then200AndMetaPinsItAtTheReleaseRevision() {
    // Arrange
    String path = "/v1/spaces/hr-acme/airlocks/hr-assistant/chat";

    // Act
    HttpResponse<String> response = post(eric, path, "{\"message\":\"what is my salary\"}");

    // Assert: the revision names the release on the response and on every tool call
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("x-aisolate-revision")).contains(catalog.revision());
    assertThat(catalog.revision()).isEqualTo(REVISION);
    assertThat(((FakeToolClient) toolServer).calls())
        .isNotEmpty()
        .allSatisfy(call -> assertThat(call.revision()).isEqualTo(catalog.revision()));
  }

  @Test
  void givenPod_whenListAirlocks_thenWholeReleaseFilteredByAdmission() {
    // Arrange
    String discovery = "/v1/airlocks";

    // Act
    HttpResponse<String> response = get(nora, discovery);

    // Assert: the pin narrows the chat endpoint, not what the release contains
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("founders-finance")
        .contains("/v1/spaces/hr-acme/airlocks/hr-assistant/chat");
  }

  @Test
  void givenPod_whenUiEndpoint_then404() {
    // Arrange: the module has no backend controller, so one write stands for all
    String uiEndpoint = "/v1/spaces";

    // Act
    HttpResponse<String> response = post(nora, uiEndpoint, "{}");

    // Assert: absent, not forbidden: an airlock pod has nothing to write
    assertThat(response.statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> get(String bearer, String path) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    return Http.send(Http.bearer(request, bearer).build());
  }

  private HttpResponse<String> post(String bearer, String path, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    return Http.send(Http.bearer(request, bearer).build());
  }
}
