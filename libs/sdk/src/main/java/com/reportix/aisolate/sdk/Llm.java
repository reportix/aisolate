package com.reportix.aisolate.sdk;

import java.util.List;

/**
 * One LLM of this airlock's catalog, from {@link AIsolateManager#llm}: the same entry chats or
 * embeds, as far as its model can. A model server that fails otherwise (unreachable, timed out, a
 * 5xx) throws as any HTTP client does.
 */
public interface Llm {

  /**
   * The text's vector, from the model server's {@code /api/embed}.
   *
   * @throws LlmException its model cannot embed
   */
  float[] embed(String text);

  /**
   * One vector per text, in order, in one request.
   *
   * @throws LlmException its model cannot embed
   */
  List<float[]> embed(List<String> texts);

  /**
   * The model's answer to {@code prompt} alone, as one user message, from {@code /api/chat}.
   *
   * @throws LlmException its model cannot chat
   */
  String chat(String prompt);
}
