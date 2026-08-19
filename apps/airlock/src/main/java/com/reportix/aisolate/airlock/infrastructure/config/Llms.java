package com.reportix.aisolate.airlock.infrastructure.config;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.sdk.Llm;
import com.reportix.aisolate.sdk.LlmException;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * The pod's LLM catalog, one client per LLM, built once at start.
 *
 * @param main the one that answers the exchange; null in a discovery pod, which serves no airlock
 */
public record Llms(CatalogLlm main, Map<String, CatalogLlm> byId) {

  static final Llms NONE = new Llms(null, Map.of());

  /**
   * @param readTimeout the model read timeout, for the clients that are not Boot's
   */
  static Llms connect(
      LlmCatalog catalog,
      RestClient.Builder http,
      ObservationRegistry observations,
      RetryTemplate retry,
      Duration readTimeout) {
    Map<String, CatalogLlm> byId =
        catalog.entries().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    LlmCatalog.Entry::id,
                    entry ->
                        AirlockDocument.OPENAI.equals(entry.provider())
                            ? new OpenAiLlm(entry, http, observations, readTimeout)
                            : new OllamaLlm(entry, http, observations, retry)));
    return new Llms(byId.get(catalog.main()), byId);
  }

  /** What {@code aisolate.llm(id)} returns. */
  public Llm get(String id) {
    CatalogLlm llm = id == null ? null : byId.get(id);
    if (llm == null) {
      throw new LlmException("no model named " + id + " in this airlock's space or globally");
    }
    return llm;
  }
}
