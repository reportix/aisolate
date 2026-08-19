package com.reportix.aisolate.airlock.infrastructure.config;

import com.reportix.aisolate.sdk.Llm;
import com.reportix.aisolate.sdk.LlmException;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.embedding.observation.DefaultEmbeddingModelObservationConvention;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationDocumentation;

/**
 * One catalog LLM's client, by its provider: the handle a handler's {@code aisolate.llm(id)}
 * returns, and the chat model the agent loop asks when it is the main one. Every model span it
 * makes carries {@code aisolate.llm}, its id, beside Spring AI's {@code gen_ai.request.model}; a
 * model asked what it cannot do, or a refused key, is the same plain {@link LlmException} whatever
 * the provider.
 */
sealed interface CatalogLlm extends Llm permits OllamaLlm, OpenAiLlm {

  String LLM = "aisolate.llm";

  String provider();

  /** What the agent loop asks when this is the main LLM. */
  ChatModel chatModel();

  static LlmException cannotEmbed(LlmCatalog.Entry llm) {
    return new LlmException(
        named(llm) + " cannot embed: name an embedding model such as nomic in aisolate.llm(...)");
  }

  static LlmException cannotChat(LlmCatalog.Entry llm) {
    return new LlmException(
        named(llm) + " cannot chat: name a chat model such as granite in aisolate.llm(...)");
  }

  /** Never the key, nor the server's answer, which may quote it masked. */
  static LlmException keyRefused(LlmCatalog.Entry llm) {
    return new LlmException(
        named(llm) + ": the API key was refused; set it again on the model in AIsolate");
  }

  static ChatModelObservationConvention chatSpans(LlmCatalog.Entry llm) {
    return new DefaultChatModelObservationConvention() {
      @Override
      public KeyValues getHighCardinalityKeyValues(ChatModelObservationContext context) {
        return super.getHighCardinalityKeyValues(context).and(LLM, llm.id());
      }
    };
  }

  static DefaultEmbeddingModelObservationConvention embeddingSpans(LlmCatalog.Entry llm) {
    return new DefaultEmbeddingModelObservationConvention() {
      @Override
      public KeyValues getHighCardinalityKeyValues(EmbeddingModelObservationContext context) {
        return super.getHighCardinalityKeyValues(context).and(LLM, llm.id());
      }
    };
  }

  /**
   * Spring AI's embedding span and token meter around an embed made by hand: {@code call} asks the
   * server, sets the context's response and returns the vectors.
   */
  static List<float[]> observeEmbedding(
      LlmCatalog.Entry llm,
      EmbeddingOptions options,
      List<String> texts,
      ObservationRegistry observations,
      DefaultEmbeddingModelObservationConvention spans,
      Function<EmbeddingModelObservationContext, List<float[]>> call) {
    EmbeddingModelObservationContext context =
        EmbeddingModelObservationContext.builder()
            .embeddingRequest(new EmbeddingRequest(texts, options))
            .provider(llm.provider())
            .build();
    return EmbeddingModelObservationDocumentation.EMBEDDING_MODEL_OPERATION
        .observation(null, spans, () -> context, observations)
        .observe(() -> call.apply(context));
  }

  /** The response a hand-made embed sets on its context, for the span and the meter. */
  static EmbeddingResponse embeddingResponse(
      LlmCatalog.Entry llm, List<float[]> vectors, Integer tokens) {
    List<Embedding> embeddings = new ArrayList<>();
    for (int i = 0; i < vectors.size(); i++) {
      embeddings.add(new Embedding(vectors.get(i), i));
    }
    return new EmbeddingResponse(
        embeddings,
        new EmbeddingResponseMetadata(
            llm.model(), new DefaultUsage(tokens == null ? 0 : tokens, 0)));
  }

  private static String named(LlmCatalog.Entry llm) {
    return llm.model() + " (model " + llm.id() + ")";
  }
}
