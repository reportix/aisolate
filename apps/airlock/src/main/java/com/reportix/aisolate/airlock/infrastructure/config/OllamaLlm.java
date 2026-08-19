package com.reportix.aisolate.airlock.infrastructure.config;

import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.observation.DefaultEmbeddingModelObservationConvention;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/** One catalog LLM on its Ollama server ({@link CatalogLlm}). */
public final class OllamaLlm implements CatalogLlm {

  private final LlmCatalog.Entry entry;
  private final ObservationRegistry observations;
  private final RestClient http;
  private final OllamaChatModel chatModel;
  private final DefaultEmbeddingModelObservationConvention embeddingSpans;

  /**
   * @param http Boot's builder, carrying the model timeouts ({@code spring.http.client.settings})
   * @param retry Spring AI's, set to none: a retried model call outlasts the exchange's budget
   */
  OllamaLlm(
      LlmCatalog.Entry entry,
      RestClient.Builder http,
      ObservationRegistry observations,
      RetryTemplate retry) {
    this.entry = entry;
    this.observations = observations;
    ResponseErrorHandler refusals = refusals(entry);
    this.http = http.clone().baseUrl(entry.url()).defaultStatusHandler(refusals).build();
    this.chatModel =
        OllamaChatModel.builder()
            .ollamaApi(
                OllamaApi.builder()
                    .baseUrl(entry.url())
                    .restClientBuilder(http)
                    .responseErrorHandler(refusals)
                    .build())
            .options(
                OllamaChatOptions.builder()
                    .model(entry.model())
                    .temperature(0.1)
                    .keepAlive("-1m")
                    .disableThinking()
                    .build())
            .observationRegistry(observations)
            .retryTemplate(retry)
            .build();
    chatModel.setObservationConvention(CatalogLlm.chatSpans(entry));
    this.embeddingSpans = CatalogLlm.embeddingSpans(entry);
  }

  @Override
  public String provider() {
    return entry.provider();
  }

  @Override
  public ChatModel chatModel() {
    return chatModel;
  }

  @Override
  public float[] embed(String text) {
    return embed(text, List.of(text)).getFirst();
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    return embed(texts, texts);
  }

  @Override
  public String chat(String prompt) {
    return chatModel.call(prompt);
  }

  /**
   * By hand, not Spring AI's embedding model, which always sends a list: {@code input} is the text
   * itself or the list, as asked. The span and the token meter are Spring AI's all the same.
   */
  private List<float[]> embed(Object input, List<String> texts) {
    return CatalogLlm.observeEmbedding(
        entry,
        OllamaEmbeddingOptions.builder().model(entry.model()).build(),
        texts,
        observations,
        embeddingSpans,
        context -> {
          OllamaApi.EmbeddingsResponse response =
              http.post()
                  .uri("/api/embed")
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(Map.of("model", entry.model(), "input", input))
                  .retrieve()
                  .body(OllamaApi.EmbeddingsResponse.class);
          List<float[]> vectors = response == null ? List.of() : response.embeddings();
          context.setResponse(
              CatalogLlm.embeddingResponse(
                  entry, vectors, response == null ? null : response.promptEvalCount()));
          return vectors;
        });
  }

  /**
   * Ollama's answers to a model asked what it cannot do, 501 on {@code /api/embed} and 400 {@code
   * does not support chat} on {@code /api/chat}, as the plain messages a developer acts on; any
   * other failure as Spring AI's own handler throws it.
   */
  private static ResponseErrorHandler refusals(LlmCatalog.Entry llm) {
    return new ResponseErrorHandler() {
      @Override
      public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
      }

      @Override
      public void handleError(URI url, HttpMethod method, ClientHttpResponse response)
          throws IOException {
        int status = response.getStatusCode().value();
        String body = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        if (status == 501 && url.getPath().endsWith("/api/embed")) {
          throw CatalogLlm.cannotEmbed(llm);
        }
        if (status == 400
            && url.getPath().endsWith("/api/chat")
            && body.contains("does not support chat")) {
          throw CatalogLlm.cannotChat(llm);
        }
        String message = "HTTP " + status + " - " + body;
        throw response.getStatusCode().is4xxClientError()
            ? new NonTransientAiException(message)
            : new TransientAiException(message);
      }
    };
  }
}
