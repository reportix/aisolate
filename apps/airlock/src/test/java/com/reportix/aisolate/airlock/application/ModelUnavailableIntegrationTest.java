package com.reportix.aisolate.airlock.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.AirlockApplication;
import com.reportix.aisolate.airlock.domain.chat.McpToolClient;
import com.reportix.aisolate.airlock.support.AirlockTestConfig;
import com.reportix.aisolate.airlock.support.FakeToolClient;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Tokens;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;

/**
 * The real Ollama client against a model server that refuses connections: the exchange ends in
 * seconds, not after Spring AI's default retries.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = AirlockApplication.class)
@ContextConfiguration(
    initializers = {
      AirlockTestConfig.class,
      ChatEndpointSecurityTest.Documents.class,
      ModelUnavailableIntegrationTest.ClosedModelPort.class
    })
@Import(ModelUnavailableIntegrationTest.ToolStub.class)
class ModelUnavailableIntegrationTest {

  static final class ClosedModelPort
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      TestPropertyValues.of("AISOLATE_LLM_0_URL=http://127.0.0.1:" + closedPort()).applyTo(context);
    }

    private static int closedPort() {
      try (ServerSocket socket = new ServerSocket(0)) {
        return socket.getLocalPort();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @TestConfiguration
  static class ToolStub {
    @Bean
    @Primary
    McpToolClient fakeToolClient() {
      return new FakeToolClient();
    }
  }

  @LocalServerPort int port;

  @Test
  void givenModelServerRefusingConnections_whenChatAsJson_then503WithinSeconds() {
    // Arrange: the request gives up long before one default back-off round would end
    HttpRequest request =
        Http.bearer(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/v1/spaces/hr-acme/airlocks/acme-hr/chat"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"salary?\"}")),
                Tokens.forUser("eric", "password"))
            .build();

    // Act
    HttpResponse<String> response = Http.send(request);

    // Assert
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("The model is not available right now");
  }
}
