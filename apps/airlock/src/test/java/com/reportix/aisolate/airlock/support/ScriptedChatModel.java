package com.reportix.aisolate.airlock.support;

import io.micrometer.observation.ObservationRegistry;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * A ChatModel that replays scripted responses and records every prompt it was given. Thread-safe:
 * the saturation tests drive two exchanges through one instance at the same time.
 */
public final class ScriptedChatModel implements ChatModel {

  private final Deque<AssistantMessage> script = new ConcurrentLinkedDeque<>();
  private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
  private volatile Runnable onCall = () -> {};
  private volatile ChatResponseMetadata metadata = ChatResponseMetadata.builder().build();
  private volatile ObservationRegistry observations = ObservationRegistry.NOOP;
  private volatile boolean structured;

  public ScriptedChatModel answer(String text) {
    script.add(new AssistantMessage(text));
    return this;
  }

  /** A final answer in the answer format: {@code text}, its {@code {f1}} the cell named. */
  public ScriptedChatModel answerCiting(String text, String evidence, String row, String column) {
    return answer(citing(text, evidence, row, column));
  }

  /** The JSON of {@link #answerCiting}; {@code text} holds no quote mark. */
  public static String citing(String text, String evidence, String row, String column) {
    return "{\"text\":\""
        + text
        + "\",\"figures\":[{\"id\":\"f1\",\"cell\":{\"evidence\":\""
        + evidence
        + "\",\"row\":\""
        + row
        + "\",\"column\":\""
        + column
        + "\"}}],\"quotes\":[],\"dont_know\":false}";
  }

  public ScriptedChatModel toolCall(String tool, String argumentsJson) {
    script.add(
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "call-" + script.size(), "function", tool, argumentsJson)))
            .build());
    return this;
  }

  /** Every response from now on reports this usage, as a provider that counts tokens does. */
  public ScriptedChatModel reportingTokens(int input, int output) {
    metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(input, output)).build();
    return this;
  }

  /** Every call from now on is observed like a provider's: the model span, with its tokens. */
  public ScriptedChatModel observedBy(ObservationRegistry registry) {
    this.observations = registry;
    return this;
  }

  /** From now on its options are Ollama's, which constrain an answer to a JSON schema. */
  public ScriptedChatModel withStructuredOutput() {
    structured = true;
    return this;
  }

  public List<Prompt> prompts() {
    return prompts;
  }

  /**
   * Runs on the exchange's own thread at the start of every model call, to hold an exchange open.
   */
  public ScriptedChatModel onCall(Runnable hook) {
    this.onCall = hook;
    return this;
  }

  public void reset() {
    script.clear();
    prompts.clear();
    onCall = () -> {};
    metadata = ChatResponseMetadata.builder().build();
    observations = ObservationRegistry.NOOP;
    structured = false;
  }

  /** Like a real provider: the configured model id lives in the model's own options. */
  @Override
  public ChatOptions getOptions() {
    return structured
        ? OllamaChatOptions.builder().model("scripted-model").temperature(0.1).build()
        : ToolCallingChatOptions.builder().model("scripted-model").temperature(0.1).build();
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    ChatModelObservationContext context =
        ChatModelObservationContext.builder().prompt(prompt).provider("scripted").build();
    return ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
        .observation(null, new DefaultChatModelObservationConvention(), () -> context, observations)
        .observe(
            () -> {
              ChatResponse response = answer(prompt);
              context.setResponse(response);
              return response;
            });
  }

  private ChatResponse answer(Prompt prompt) {
    prompts.add(prompt);
    onCall.run();
    AssistantMessage scripted = script.poll();
    if (scripted == null) {
      throw new IllegalStateException("scripted model has no more responses");
    }
    return new ChatResponse(List.of(new Generation(scripted)), metadata);
  }
}
