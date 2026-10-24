package com.reportix.aisolate.airlock.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.support.Observations;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiLlmTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String TOOL_CALL =
      """
      {"id":"chatcmpl-1","object":"chat.completion","created":1760000000,"model":"gpt-4o-mini",
       "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant",
        "content":null,"tool_calls":[{"id":"call_2","type":"function",
        "function":{"name":"read_records","arguments":"{\\"who\\":\\"elena\\"}"}}]}}],
       "usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}\
      """;

  private static final String ANSWER =
      """
      {"id":"chatcmpl-2","object":"chat.completion","created":1760000000,"model":"gpt-4o-mini",
       "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",
        "content":"{\\"text\\":\\"Twelve days.\\"}"}}],
       "usage":{"prompt_tokens":9,"completion_tokens":3,"total_tokens":12}}\
      """;

  private static final String EMBEDDINGS =
      """
      {"object":"list","data":[{"object":"embedding","embedding":[0.5],"index":0}],
       "model":"text-embedding-3-small","usage":{"prompt_tokens":2,"total_tokens":2}}\
      """;

  private final List<JsonNode> received = new CopyOnWriteArrayList<>();
  private final List<String> bearers = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void givenAToolRound_whenTheLoopAsksTheMainLlm_thenToolsCallsAndResultsGoInOpenAisFormat()
      throws IOException {
    // Arrange: as the agent loop asks, the model asking for read_records once, then answering
    OpenAiLlm llm = serve(List.of(TOOL_CALL, ANSWER), ObservationRegistry.NOOP);
    ChatClient client =
        ChatClient.builder(
                llm.chatModel(),
                ObservationRegistry.NOOP,
                null,
                null,
                ToolCallingAdvisor.builder()
                    .toolCallingManager(ToolCallingManager.builder().build()))
            .build();

    // Act
    ChatResponse reply =
        client
            .prompt()
            .messages(new UserMessage("How many holidays has Elena left?"))
            .tools(readRecords())
            .call()
            .chatResponse();

    // Assert: one function tool offered, with its key
    assertThat(received).hasSize(2);
    assertThat(bearers).containsOnly("Bearer sk-test-1234");
    JsonNode first = received.getFirst();
    assertThat(first.path("model").asString()).isEqualTo("gpt-4o-mini");
    assertThat(first.has("temperature")).isFalse();
    JsonNode tool = first.path("tools").get(0);
    assertThat(tool.path("type").asString()).isEqualTo("function");
    assertThat(tool.path("function").path("name").asString()).isEqualTo("read_records");
    assertThat(tool.path("function").path("parameters").path("type").asString())
        .isEqualTo("object");
    // The call the reply asked for and its result, tied by the reply's id
    JsonNode messages = received.get(1).path("messages");
    JsonNode call = messages.get(1).path("tool_calls").get(0);
    assertThat(call.path("id").asString()).isEqualTo("call_2");
    assertThat(call.path("function").path("name").asString()).isEqualTo("read_records");
    assertThat(call.path("function").path("arguments").asString()).isEqualTo("{\"who\":\"elena\"}");
    JsonNode result = messages.get(2);
    assertThat(result.path("role").asString()).isEqualTo("tool");
    assertThat(result.path("tool_call_id").asString()).isEqualTo("call_2");
    assertThat(result.path("content").asString()).contains("12 days");
    assertThat(reply.getResult().getOutput().getText()).isEqualTo("{\"text\":\"Twelve days.\"}");
    assertThat(reply.getMetadata().getUsage().getPromptTokens()).isEqualTo(29);
  }

  @Test
  void givenTheFinalTurn_whenAskedInTheAnswerFormat_thenTheSchemaGoesAsJsonSchemaNotStrict()
      throws IOException {
    // Arrange: what the agent loop's askInFormat asks
    OpenAiLlm llm = serve(ANSWER, ObservationRegistry.NOOP);
    String schema =
        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
            + "\"required\":[\"text\"]}";
    Prompt prompt =
        new Prompt(
            List.of(new UserMessage("Answer in the format.")),
            ((StructuredOutputChatOptions) llm.chatModel().getOptions())
                .mutate()
                .outputSchema(schema)
                .build());

    // Act
    String answer = llm.chatModel().call(prompt).getResult().getOutput().getText();

    // Assert: OpenAI's strict mode would refuse the answer format's optional fields
    JsonNode format = received.getFirst().path("response_format");
    assertThat(format.path("type").asString()).isEqualTo("json_schema");
    assertThat(format.path("json_schema").path("strict").asBoolean(true)).isFalse();
    assertThat(format.path("json_schema").path("schema")).isEqualTo(JSON.readTree(schema));
    assertThat(answer).isEqualTo("{\"text\":\"Twelve days.\"}");
  }

  @ParameterizedTest
  @CsvSource({"embed, embedding gpt-4o-mini", "chat, chat gpt-4o-mini"})
  void givenAnLlm_whenCodeCallsIt_thenItsModelSpanNamesTheLlmBesideTheModel(String op, String span)
      throws IOException {
    // Arrange
    List<Observation.Context> spans = new CopyOnWriteArrayList<>();
    OpenAiLlm llm = serve(op.equals("embed") ? EMBEDDINGS : ANSWER, Observations.recording(spans));

    // Act
    if (op.equals("embed")) {
      llm.embed("salary");
    } else {
      llm.chat("hello");
    }

    // Assert
    assertThat(spans)
        .filteredOn(context -> span.equals(context.getContextualName()))
        .singleElement()
        .satisfies(
            context -> {
              assertThat(context.getHighCardinalityKeyValue("aisolate.llm").getValue())
                  .isEqualTo("gpt");
              assertThat(context.getLowCardinalityKeyValue("gen_ai.request.model").getValue())
                  .isEqualTo("gpt-4o-mini");
            });
  }

  private OpenAiLlm serve(String body, ObservationRegistry registry) throws IOException {
    return serve(List.of(body), registry);
  }

  /**
   * The LLM gpt on a stub at {@code /v1} that answers with {@code bodies} in turn, the last again.
   */
  private OpenAiLlm serve(List<String> bodies, ObservationRegistry registry) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          received.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
          bearers.add(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] answer =
              bodies
                  .get(Math.min(received.size(), bodies.size()) - 1)
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    server.start();
    return new OpenAiLlm(
        new LlmCatalog.Entry(
            "gpt",
            "openai",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "gpt-4o-mini",
            "sk-test-1234"),
        RestClient.builder(),
        registry,
        Duration.ofSeconds(10));
  }

  private static ToolCallback readRecords() {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("read_records")
            .description("Reads an employee's HR records")
            .inputSchema(
                "{\"type\":\"object\",\"properties\":{\"who\":{\"type\":\"string\"}},"
                    + "\"required\":[\"who\"]}")
            .build();
      }

      @Override
      public String call(String input) {
        return "12 days";
      }
    };
  }
}
