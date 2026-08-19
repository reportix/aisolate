package com.reportix.aisolate.airlock.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.AirlockApplication;
import com.reportix.aisolate.airlock.support.AirlockTestConfig;
import com.reportix.aisolate.airlock.support.Documents;
import com.reportix.aisolate.airlock.support.ScriptedChatModel;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DiscoveredAirlock;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Tokens;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * The discovery pod as the controller renders it: the discovery mount and no documents, so no
 * airlock's code, data source or model. Every user's token reaches it through the shared Service.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = AirlockApplication.class)
@ContextConfiguration(
    initializers = {AirlockTestConfig.class, DiscoveryPodIntegrationTest.Pod.class})
@Import(ChatEndpointSecurityTest.Stubs.class)
class DiscoveryPodIntegrationTest {

  /** One space's file, as the controller projects it into the mount. */
  static final class Pod implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      try {
        Path mount = Files.createTempDirectory("aisolate-discovery");
        Files.writeString(
            mount.resolve("hr-acme.json"),
            DiscoveredAirlock.toJson(
                DiscoveredAirlock.of(ConfigurationLoader.load(Documents.DIR))));
        TestPropertyValues.of("aisolate.config.discovery-dir=" + mount).applyTo(context);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @LocalServerPort int port;
  @Autowired ScriptedChatModel model;

  private final String nora = Tokens.forUser("nora", "password");

  @Test
  void givenDiscoveryPod_whenListAirlocks_thenTheMountsAirlocksAdmittingTheToken() {
    // Arrange
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/airlocks")).GET();

    // Act
    HttpResponse<String> response = Http.send(Http.bearer(request, nora).build());

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("founders-finance")
        .contains("\"space\":\"hr-acme\"")
        .contains("/v1/spaces/hr-acme/airlocks/hr-assistant/chat");
  }

  @Test
  void givenNoToken_whenListAirlocks_then401() {
    // Arrange
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/airlocks"))
            .GET()
            .build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert
    assertThat(response.statusCode()).isEqualTo(401);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/v1/spaces/hr-acme/airlocks/hr-assistant/chat",
        "/v1/spaces/hr-acme/airlocks/retired/chat"
      })
  void givenDiscoveryPod_whenChat_then404AndNoModelCall(String path) {
    // Arrange: a listed airlock whose own Ingress rule is missing, and one nobody lists
    model.reset();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"what is my salary\"}"));

    // Act
    HttpResponse<String> response = Http.send(Http.bearer(request, nora).build());

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("x-aisolate-revision")).isEmpty();
    assertThat(model.prompts()).isEmpty();
  }
}
