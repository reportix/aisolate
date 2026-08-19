package com.reportix.aisolate.airlock.domain.chat;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditEvent;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditLog;
import com.reportix.aisolate.plugins.HandlerCandidate;
import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.LlmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * AIsolate's side of one chat exchange: which model answers, the one push towards the tools, the
 * exchange's budget and gate 6. Spring AI's tool-calling advisor runs the model-and-tool loop
 * itself; only what is AIsolate's own lives here.
 *
 * <p>Who sees what:
 *
 * <ul>
 *   <li><b>The model</b> sees the platform preamble, the airlock's system prompt, the context and
 *       passages the developer's handlers added, the user's message, the schemas of the airlock's
 *       tools and the rows the tool server returned. AIsolate itself adds no token, subject, e-mail
 *       or role.
 *   <li><b>The tool server</b> sees every {@code tools/list} and {@code tools/call}, each carrying
 *       the user's bearer token. The MCP client reads that token from the request's security
 *       context at the call site; nothing in this package carries, names or can read it, and
 *       architecture tests keep both Spring Security and the infrastructure package out of here.
 *   <li><b>The browser</b> sees {@link ChatEvent}s as they happen: {@code tool_call}, {@code
 *       evidence} without rows, then {@code answer_chunk}, {@code verification} when a verifier
 *       ran, and {@code done}, or {@code error}. The tool events arrive while the exchange is still
 *       running, because the framework executes tools on this same thread.
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class AgentLoopService {

  /**
   * Tool calls one exchange may make. This is what bounds the loop: the framework asks the model
   * again only while it keeps requesting tools, so capping the calls caps the rounds at one more
   * than this. Exceeding it ends the tool use: the model is asked once more, with no tool to call,
   * to answer from what the tools returned.
   */
  static final int MAX_TOOL_CALLS_PER_EXCHANGE = 3;

  /** The one provider client Spring AI built, for {@code provider}; null without one. */
  private final ChatModel chatModel;

  /**
   * {@code spring.ai.model.chat}: the provider id an airlock names to run on {@link #chatModel}.
   */
  private final String provider;

  private final McpToolClient toolServer;
  private final HandlerCatalog handlerCatalog;
  private final JsonMapper jsonMapper;
  private final Duration exchangeMaxDuration;
  private final ExchangeTracing tracing;
  private final AuditLog auditLog;

  /**
   * Runs one exchange to completion and streams its events to {@code eventSink}. The response is
   * already committed when this runs, so a failure here can only become an {@code error} event;
   * nothing is thrown to the caller.
   *
   * @param airlock the airlock the user talks to; {@link
   *     com.reportix.aisolate.airlock.domain.airlock.AirlockAdmissionService} has already checked
   *     that the user is one of its space's users or its airlock's own, and the user's client one
   *     of its airlock's callers
   * @param userMessage the question as the user typed it
   * @param manager what the developer's handlers may know about this exchange; built outside this
   *     package, so the runtime never touches identity itself
   * @param client the OAuth client the token was issued to ({@code azp}), for the audit line only;
   *     the handlers are never told
   */
  public void answerUserMessage(
      AirlockDefinition airlock,
      String userMessage,
      AIsolateManager manager,
      String client,
      Consumer<ChatEvent> eventSink) {
    Instant started = Instant.now();
    ExchangeAudit audit =
        new ExchangeAudit(airlock, manager.principal().email(), client, userMessage);
    Consumer<ChatEvent> watched =
        event -> {
          audit.accept(event);
          eventSink.accept(event);
        };
    try {
      runExchange(airlock, userMessage, manager, audit, started, watched);
    } finally {
      // The one place every outcome passes: streamed or collected, answered or failed.
      AuditEvent line =
          audit.toEvent(
              started, Duration.between(started, Instant.now()).toMillis(), tracing.traceId());
      tracing.finished(line.outcome().json(), line.figures());
      auditLog.record(line);
    }
  }

  private void runExchange(
      AirlockDefinition airlock,
      String userMessage,
      AIsolateManager manager,
      ExchangeAudit audit,
      Instant started,
      Consumer<ChatEvent> eventSink) {
    // Limit: one provider client; a map by provider id once a second provider exists.
    ChatModel chatModel =
        this.chatModel != null && provider.equals(airlock.model())
            ? audit.meter(this.chatModel)
            : null;
    if (chatModel == null) {
      // Fail closed: an exchange never quietly runs on a model the airlock did not name.
      eventSink.accept(ChatEvent.Error.NO_MODEL_ENDPOINT);
      return;
    }
    // Before the handlers: a slow first load of a jar set spends the budget too.
    Instant deadline = Instant.now().plus(exchangeMaxDuration);
    Handlers handlers;
    try {
      handlers = handlerCatalog.resolveHandlers(airlock);
    } catch (RuntimeException failed) {
      // Fail closed: an airlock whose jar is broken answers nothing rather than something else.
      // Message only: the catalog logged the stack trace when the load failed.
      log.warn(
          "handlers of {}/{} could not be loaded: {}",
          airlock.spaceId(),
          airlock.id(),
          failed.getMessage());
      eventSink.accept(ChatEvent.Error.COULD_NOT_COMPLETE);
      return;
    }
    // The pod's log is the operator's view of an exchange: what ran, never what was said.
    log.info(
        "exchange_start airlock={}/{} revision={} tools={} data_sources={}",
        airlock.spaceId(),
        airlock.id(),
        airlock.revision(),
        airlock.tools(),
        airlock.dataSources());
    log.info(
        "handlers_resolved airlock={}/{} context_prep={} memory={} verifier={}",
        airlock.spaceId(),
        airlock.id(),
        listNames(handlers.contextPreparations()),
        listNames(handlers.memories()),
        listNames(handlers.verifiers()));
    ChatExchange exchange =
        new ChatExchange(airlock, toolServer, jsonMapper, eventSink, deadline, tracing);
    try {
      ChatClient chatClient = buildChatClient(chatModel, exchange);
      ChatResponse reply = ask(chatClient, exchange, exchange.open(userMessage, handlers, manager));
      if (exchange.answeredWithoutTools()) {
        reply = ask(chatClient, exchange, exchange.nudgeTowardsTools(extractText(reply)));
      }
      if (ranOutOfToolCalls(reply)) {
        // The last round: no tool callbacks, so the model can only answer.
        reply = chatClient.prompt().messages(exchange.answerFromEvidence()).call().chatResponse();
      }
      if (ranOutOfToolCalls(reply)) {
        exchange.giveUp();
      } else {
        exchange.answer(
            exchange.ground(
                extractText(reply),
                conversation -> askInFormat(chatClient, chatModel, conversation)),
            handlers,
            manager);
      }
      log.info(
          "exchange_done airlock={}/{} ms={}",
          airlock.spaceId(),
          airlock.id(),
          Duration.between(started, Instant.now()).toMillis());
    } catch (ExchangeDeadlineExceededException spent) {
      audit.failedWith("deadline");
      exchange.giveUp();
    } catch (RuntimeException failed) {
      // The model server is the one dependency the user can act on: say so, the way a hosted
      // assistant does. Anything else is ours, and the stream is already open, so it can only be
      // a generic error event with the cause in the log.
      if (modelServerFailed(failed)) {
        log.warn(
            "model endpoint {} failed with {}", airlock.model(), failed.getClass().getSimpleName());
        eventSink.accept(ChatEvent.Error.MODEL_UNAVAILABLE);
        return;
      }
      log.error("exchange failed for {}/{}", airlock.spaceId(), airlock.id(), failed);
      exchange.giveUp();
    } catch (Error fatal) {
      // The stream is committed: its client gets an end before the JVM's own handling.
      exchange.giveUp();
      throw fatal;
    }
  }

  /**
   * The same exchange for a caller that does not stream: the events collected into one {@link
   * ChatAnswer}.
   *
   * @throws ExchangeFailedException the exchange ended in an {@code error} event
   */
  public ChatAnswer collectAnswer(
      AirlockDefinition airlock, String userMessage, AIsolateManager manager, String client) {
    List<ChatEvent> events = new ArrayList<>();
    answerUserMessage(airlock, userMessage, manager, client, events::add);
    return ChatAnswer.from(events, airlock.revision(), tracing.traceId());
  }

  /**
   * The model behind a tool-calling manager of this exchange alone, so that resolving an unknown
   * name to a refusal is true of this exchange and not of every other tool caller in the Spring
   * context, and so the resolver can tell the exchange that the model did reach for a tool.
   */
  private ChatClient buildChatClient(ChatModel chatModel, ChatExchange exchange) {
    ToolCallingManager toolCalling =
        ToolCallingManager.builder()
            .observationRegistry(tracing.registry())
            .toolCallbackResolver(exchange::resolveUnknownTool)
            .resolutionFallbackEnabled(true)
            .maxTotalToolCalls(MAX_TOOL_CALLS_PER_EXCHANGE)
            .onLimitExceeded(ToolCallLimitBehavior.THROW)
            .build();
    // null, null: the default observation conventions. This overload is the one that replaces the
    // framework's default ToolCallingAdvisor with ours.
    return ChatClient.builder(
            chatModel,
            tracing.registry(),
            null,
            null,
            ToolCallingAdvisor.builder().toolCallingManager(toolCalling))
        .build();
  }

  /**
   * Whether the provider client could not get an answer out of the model server: refused, timed
   * out, unreachable, or out of retries. The message is never read, only the type: a provider
   * exception quotes the response body, which from round two holds evidence rows.
   */
  private static boolean modelServerFailed(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof RestClientException
          || cause instanceof TransientAiException
          || cause instanceof NonTransientAiException
          || cause instanceof RetryException
          // A main LLM whose model cannot chat: Ollama refused it.
          || cause instanceof LlmException) {
        return true;
      }
    }
    return false;
  }

  private static ChatResponse ask(
      ChatClient chatClient, ChatExchange exchange, List<Message> conversation) {
    return chatClient
        .prompt()
        .messages(conversation)
        .tools(exchange.listToolCallbacks())
        // call(), not stream(): the tool callbacks must run on this thread, where the MCP client
        // reads the user's security context (see McpSyncToolClient).
        .call()
        .chatResponse();
  }

  /**
   * The final turn: no tools, the answer constrained to the answer format's JSON schema where the
   * provider has structured output (Ollama's {@code format}); elsewhere it is asked for in words
   * alone and parsed all the same.
   */
  private static String askInFormat(
      ChatClient chatClient, ChatModel chatModel, List<Message> conversation) {
    ChatClient.ChatClientRequestSpec request = chatClient.prompt().messages(conversation);
    if (chatModel.getOptions() instanceof StructuredOutputChatOptions structured) {
      request = request.options(structured.mutate().outputSchema(AnswerFormat.SCHEMA));
    }
    return extractText(request.call().chatResponse());
  }

  /**
   * The model kept asking for tools until the exchange's budget of them was gone. The advisor turns
   * the manager's {@link ToolCallLimitExceededException} into this finish reason itself.
   */
  private static boolean ranOutOfToolCalls(ChatResponse reply) {
    Generation result = reply == null ? null : reply.getResult();
    return result != null
        && ToolCallLimitExceededException.FINISH_REASON.equals(
            result.getMetadata().getFinishReason());
  }

  private static String extractText(ChatResponse reply) {
    Generation result = reply == null ? null : reply.getResult();
    return result == null ? "" : result.getOutput().getText();
  }

  private static List<String> listNames(List<HandlerCandidate> candidates) {
    return candidates.stream().map(HandlerCandidate::name).toList();
  }
}
