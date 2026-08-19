package com.reportix.aisolate.airlock.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.support.Observations;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.web.client.RestClient;

class OllamaLlmTest {

  @ParameterizedTest
  @CsvSource({"embed, embedding nomic-embed-text", "chat, chat nomic-embed-text"})
  void givenAnLlm_whenCodeCallsIt_thenItsModelSpanNamesTheLlmBesideTheModel(String op, String span)
      throws IOException {
    // Arrange
    HttpServer ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ollama.createContext(
        "/",
        exchange -> {
          byte[] body =
              (exchange.getRequestURI().getPath().endsWith("/api/embed")
                      ? "{\"model\":\"nomic-embed-text\",\"embeddings\":[[0.5]],\"prompt_eval_count\":2}"
                      : "{\"model\":\"nomic-embed-text\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"done\":true}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    ollama.start();
    List<Observation.Context> spans = new CopyOnWriteArrayList<>();
    OllamaLlm llm =
        new OllamaLlm(
            new LlmCatalog.Entry(
                "nomic",
                "ollama",
                "http://127.0.0.1:" + ollama.getAddress().getPort(),
                "nomic-embed-text"),
            RestClient.builder(),
            Observations.recording(spans),
            RetryUtils.SHORT_RETRY_TEMPLATE);

    // Act
    try {
      if (op.equals("embed")) {
        llm.embed("salary");
      } else {
        llm.chat("hello");
      }
    } finally {
      ollama.stop(0);
    }

    // Assert
    assertThat(spans)
        .filteredOn(context -> span.equals(context.getContextualName()))
        .singleElement()
        .satisfies(
            context -> {
              assertThat(context.getHighCardinalityKeyValue("aisolate.llm").getValue())
                  .isEqualTo("nomic");
              assertThat(context.getLowCardinalityKeyValue("gen_ai.request.model").getValue())
                  .isEqualTo("nomic-embed-text");
            });
  }
}
