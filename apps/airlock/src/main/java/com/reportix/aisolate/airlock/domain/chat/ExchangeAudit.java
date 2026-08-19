package com.reportix.aisolate.airlock.domain.chat;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditEvent;
import com.reportix.aisolate.config.Answers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * What the audit line of one exchange is made of, gathered while it runs: the events the stream
 * carries (tools, evidence, the answer, the verdicts) and, from {@link #meter}, the model calls and
 * their tokens. It only watches; it adds nothing to what the model or the user sees.
 *
 * <p>Not thread-safe, like the exchange it follows: one request thread runs it start to finish.
 */
final class ExchangeAudit implements Consumer<ChatEvent> {

  /** An evidence id as the answer cites it, {@code [ev-1]}; {@code ev-12} is never {@code ev-1}. */
  private static final Pattern CITATION = Pattern.compile("\\bev-\\d+\\b");

  private final AirlockDefinition airlock;
  private final String user;
  private final String client;
  private final String question;

  /** The tool calls by call id, in order; the evidence of a call fills in its rows. */
  private final Map<String, AuditEvent.Tool> tools = new LinkedHashMap<>();

  /** Each call's arguments, recorded on its tool once its evidence shows the server ran them. */
  private final Map<String, Map<String, Object>> arguments = new HashMap<>();

  private final List<String> evidence = new ArrayList<>();

  /** The evidence the answer's figures, tables and quotes rest on, from its {@code done}. */
  private final Set<String> referenced = new HashSet<>();

  private final List<AuditEvent.Verifier> verifiers = new ArrayList<>();
  private StringBuilder answer;
  private AuditEvent.Figures figures;
  private Map<String, Object> grounding;
  private String failure;

  private String modelName;
  private int modelCalls;
  private Integer inputTokens;
  private Integer outputTokens;

  ExchangeAudit(AirlockDefinition airlock, String user, String client, String question) {
    this.airlock = airlock;
    this.user = user;
    this.client = client;
    this.question = question;
  }

  @Override
  public void accept(ChatEvent event) {
    switch (event) {
      // The data source arrives with the call's evidence: the tool server holds the plugin naming
      // it.
      case ChatEvent.ToolCall call -> {
        tools.put(
            call.id(), new AuditEvent.Tool(call.tool(), null, null, null, null, null, null, null));
        arguments.put(call.id(), call.args());
      }
      case ChatEvent.Evidence found -> {
        evidence.add(found.id());
        tools.computeIfPresent(
            found.callId(),
            (callId, tool) ->
                new AuditEvent.Tool(
                    tool.name(),
                    arguments.get(callId),
                    found.dataSource(),
                    found.rowCount(),
                    found.ruleVersion(),
                    found.sqlHash(),
                    found.rowsHash(),
                    null));
      }
      case ChatEvent.ToolError failed ->
          tools.computeIfPresent(
              failed.callId(),
              (callId, tool) ->
                  new AuditEvent.Tool(
                      tool.name(),
                      null,
                      failed.dataSource(),
                      null,
                      null,
                      null,
                      null,
                      failed.error()));
      case ChatEvent.AnswerChunk chunk -> {
        answer = answer == null ? new StringBuilder() : answer;
        answer.append(chunk.content());
      }
      case ChatEvent.Verification verification ->
          verification
              .verdicts()
              .forEach(v -> verifiers.add(new AuditEvent.Verifier(v.handler(), v.status())));
      case ChatEvent.Error error -> failure = failure != null ? failure : codeOf(error);
      case ChatEvent.Done done -> {
        figures =
            new AuditEvent.Figures(
                done.provenance().sourced(),
                done.provenance().computed(),
                done.provenance().unsourced(),
                done.provenance().mismatch());
        referenced.addAll(referencedBy(done));
        grounding = new LinkedHashMap<>();
        grounding.put("segments", done.segments());
        grounding.put("figures", done.figures());
        grounding.put("tables", done.tables());
        grounding.put("quotes", done.quotes());
      }
    }
  }

  /** Names why the error that follows happened, where its fixed sentence alone would not. */
  void failedWith(String reason) {
    failure = reason;
  }

  /** The same model, counting every call and the tokens the provider reports for it. */
  ChatModel meter(ChatModel model) {
    return new ChatModel() {
      @Override
      public ChatResponse call(Prompt prompt) {
        modelCalls++;
        ChatOptions options = prompt.getOptions();
        if (options != null && options.getModel() != null) {
          modelName = options.getModel();
        }
        ChatResponse response = model.call(prompt);
        Usage usage = response.getMetadata().getUsage();
        if (usage != null && !(usage instanceof EmptyUsage)) {
          inputTokens = sum(inputTokens, usage.getPromptTokens());
          outputTokens = sum(outputTokens, usage.getCompletionTokens());
        }
        return response;
      }

      @Override
      public ChatOptions getOptions() {
        return model.getOptions();
      }
    };
  }

  AuditEvent toEvent(Instant began, long durationMs, String traceId) {
    String said = answer == null ? null : answer.toString();
    AuditEvent.Outcome outcome =
        failure != null || said == null
            ? AuditEvent.Outcome.ERROR
            : Answers.isDontKnow(said) ? AuditEvent.Outcome.DONT_KNOW : AuditEvent.Outcome.ANSWERED;
    String reason =
        outcome == AuditEvent.Outcome.ERROR ? (failure != null ? failure : "internal") : null;
    return new AuditEvent(
        began,
        AuditEvent.Event.EXCHANGE,
        traceId,
        airlock.spaceId(),
        airlock.id(),
        airlock.revision(),
        user,
        client,
        question,
        said,
        outcome,
        reason,
        List.copyOf(tools.values()),
        cited(said),
        verifiers,
        outcome == AuditEvent.Outcome.ERROR ? null : figures,
        outcome == AuditEvent.Outcome.ERROR ? null : grounding,
        new AuditEvent.Model(airlock.model(), modelName, modelCalls, inputTokens, outputTokens),
        durationMs);
  }

  /**
   * The evidence the answer cites, of what the exchange produced: what the report counts as cited
   * to the user. A figure, table or quote that holds cites its evidence, as does an {@code [ev-n]}
   * the text still names; an evidence the model was given and left out is no ground the answer
   * claims, and neither is one only a mismatched reference names.
   */
  private List<String> cited(String said) {
    Set<String> named =
        said == null
            ? new HashSet<>()
            : CITATION.matcher(said).results().map(MatchResult::group).collect(Collectors.toSet());
    named.addAll(referenced);
    return evidence.stream().filter(named::contains).toList();
  }

  private static Set<String> referencedBy(ChatEvent.Done done) {
    Set<String> ids = new HashSet<>();
    for (GroundedAnswer.Figure figure : done.figures()) {
      if (Grounding.SOURCED.equals(figure.status()) || Grounding.COMPUTED.equals(figure.status())) {
        if (figure.source() != null) {
          ids.add(figure.source().evidence());
        }
        if (figure.computed() != null && figure.computed().of() != null) {
          figure.computed().of().forEach(operand -> ids.add(operand.evidence()));
        }
        if (figure.computed() != null && figure.computed().by() != null) {
          ids.add(figure.computed().by().evidence());
        }
      }
    }
    done.tables().stream()
        .filter(table -> Grounding.SOURCED.equals(table.status()))
        .forEach(table -> ids.add(table.evidence()));
    done.quotes().stream()
        .filter(quote -> "quoted".equals(quote.status()))
        .forEach(quote -> ids.add(quote.evidence()));
    return ids;
  }

  private static String codeOf(ChatEvent.Error error) {
    if (error.equals(ChatEvent.Error.MODEL_UNAVAILABLE)) {
      return "llm_unavailable";
    }
    return error.equals(ChatEvent.Error.NO_MODEL_ENDPOINT) ? "no_model_endpoint" : "internal";
  }

  private static Integer sum(Integer total, Integer more) {
    return more == null ? total : (total == null ? 0 : total) + more;
  }
}
