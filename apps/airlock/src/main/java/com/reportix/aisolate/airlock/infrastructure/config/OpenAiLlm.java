package com.reportix.aisolate.airlock.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.openai.core.Timeout;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.observation.DefaultEmbeddingModelObservationConvention;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * One catalog LLM on a server that speaks OpenAI's API ({@link CatalogLlm}): OpenAI's own, or the
 * compatible endpoint of Gemini, Ollama ({@code <ollama>/v1}) and the like. Its URL is the API's
 * base, version included. Chat is Spring AI's OpenAI model ({@code <url>/chat/completions}), embed
 * is made by hand ({@code <url>/embeddings}), as Ollama's; both send the API key as the bearer, and
 * nothing without one.
 */
public final class OpenAiLlm implements CatalogLlm {

  private final LlmCatalog.Entry entry;
  private final ObservationRegistry observations;
  private final RestClient http;
  private final ChatModel chatModel;
  private final DefaultEmbeddingModelObservationConvention embeddingSpans;

  /**
   * @param http Boot's builder, carrying the model timeouts ({@code spring.http.client.settings})
   * @param readTimeout the same read timeout for the chat model, whose OpenAI client is its own
   */
  OpenAiLlm(
      LlmCatalog.Entry entry,
      RestClient.Builder http,
      ObservationRegistry observations,
      Duration readTimeout) {
    this.entry = entry;
    this.observations = observations;
    RestClient.Builder embeds =
        http.clone().baseUrl(entry.url()).defaultStatusHandler(refusals(entry));
    if (entry.apiKey() != null) {
      embeds.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + entry.apiKey());
    }
    this.http = embeds.build();
    OpenAiChatModel model =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .baseUrl(entry.url())
                    // Empty, not null: null would read OPENAI_API_KEY; empty sends no header.
                    .apiKey(entry.apiKey() == null ? "" : entry.apiKey())
                    .model(entry.model())
                    .temperature(0.1)
                    // As Ollama's: a retried model call outlasts the exchange's budget.
                    .maxRetries(0)
                    .build())
            .observationRegistry(observations)
            .httpClientBuilderCustomizer(
                client ->
                    client.timeout(
                        Timeout.builder().connect(Duration.ofSeconds(5)).read(readTimeout).build()))
            .build();
    model.setObservationConvention(CatalogLlm.chatSpans(entry));
    this.chatModel = refusing(model, entry);
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

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Embeddings(List<Datum> data, Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Datum(float[] embedding, int index) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") Integer promptTokens) {}
  }

  /** {@code input} is the text itself or the list, as asked; vectors in the order of the texts. */
  private List<float[]> embed(Object input, List<String> texts) {
    return CatalogLlm.observeEmbedding(
        entry,
        OpenAiEmbeddingOptions.builder().model(entry.model()).build(),
        texts,
        observations,
        embeddingSpans,
        context -> {
          Embeddings response =
              http.post()
                  .uri("/embeddings")
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(Map.of("model", entry.model(), "input", input))
                  .retrieve()
                  .body(Embeddings.class);
          List<float[]> vectors =
              response == null || response.data() == null
                  ? List.of()
                  : response.data().stream()
                      .sorted(Comparator.comparingInt(Embeddings.Datum::index))
                      .map(Embeddings.Datum::embedding)
                      .toList();
          context.setResponse(
              CatalogLlm.embeddingResponse(
                  entry,
                  vectors,
                  response == null || response.usage() == null
                      ? null
                      : response.usage().promptTokens()));
          return vectors;
        });
  }

  /**
   * Spring AI's model as the loop and a handler read it: the OpenAI client's exceptions become the
   * plain messages ({@link CatalogLlm}) or Spring AI's own, and the final turn's schema is asked
   * for loosely, as Ollama's {@code format} is: OpenAI's strict mode refuses a schema with optional
   * fields, which the answer format has.
   */
  private static ChatModel refusing(OpenAiChatModel model, LlmCatalog.Entry entry) {
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        try {
          return model.call(notStrict(prompt));
        } catch (OpenAIServiceException refused) {
          int status = refused.statusCode();
          if (status == 401) {
            throw CatalogLlm.keyRefused(entry);
          }
          if (status == 400 && String.valueOf(refused.body()).contains("does not support chat")) {
            throw CatalogLlm.cannotChat(entry);
          }
          // The status alone: the body may quote the conversation, or the key masked.
          throw status < 500
              ? new NonTransientAiException("HTTP " + status)
              : new TransientAiException("HTTP " + status);
        } catch (OpenAIException failed) {
          throw new TransientAiException(failed.getClass().getSimpleName());
        }
      }

      @Override
      public ChatOptions getOptions() {
        return model.getOptions();
      }
    };
  }

  private static Prompt notStrict(Prompt prompt) {
    if (!(prompt.getOptions() instanceof OpenAiChatOptions options)
        || options.getResponseFormat() == null
        || options.getResponseFormat().getStrict() != null) {
      return prompt;
    }
    OpenAiChatModel.ResponseFormat format = options.getResponseFormat();
    return prompt
        .mutate()
        .chatOptions(
            options
                .mutate()
                .responseFormat(
                    OpenAiChatModel.ResponseFormat.builder()
                        .type(format.getType())
                        .jsonSchema(format.getJsonSchema())
                        .strict(false)
                        .build())
                .build())
        .build();
  }

  /**
   * The server's answers to a model asked what it cannot do and to a refused key, as the plain
   * messages; any other failure as Spring AI's exceptions, by status alone: the body may quote the
   * key masked.
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
        if (status == 401) {
          throw CatalogLlm.keyRefused(llm);
        }
        if (status == 501) {
          throw CatalogLlm.cannotEmbed(llm);
        }
        throw response.getStatusCode().is4xxClientError()
            ? new NonTransientAiException("HTTP " + status)
            : new TransientAiException("HTTP " + status);
      }
    };
  }
}
