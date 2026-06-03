package com.reportix.aisolate.config;

/**
 * An LLM an airlock may use, by id: globally, on a space or the airlock, the nearest definition of
 * an id winning ({@link Configuration#llmsOf}). It has no type: the same entry chats or embeds, as
 * far as its model can.
 *
 * @param provider one of {@link AirlockDocument#PROVIDERS}, which the airlock pod has a client for
 * @param url the model server's base URL, under the same rules as a data source's; an {@code
 *     openai} one's ends in its API version, {@code https://api.openai.com/v1}
 * @param model the name the server knows, for example {@code granite4.1:8b}
 * @param secretRef the Secret whose key {@code apiKey} holds its API key; null without one. The key
 *     never sits in a document.
 */
public record Llm(String id, String provider, String url, String model, String secretRef) {

  /** An LLM without an API key. */
  public Llm(String id, String provider, String url, String model) {
    this(id, provider, url, model, null);
  }
}
