package com.reportix.aisolate.airlock.domain.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditEvent;
import com.reportix.aisolate.config.Answers;
import com.reportix.aisolate.sdk.Verdict.Status;
import java.util.ArrayList;
import java.util.List;

/**
 * One exchange as one document, for callers that do not stream (a workflow tool): what the {@link
 * ChatEvent}s said, collected. Evidence is provenance only, never rows or text, as on the stream.
 *
 * @param dontKnow the answer is empty or begins with "I don't know" ({@link Answers})
 * @param segments the answer as text, figures and tables, with {@code figures}, {@code tables},
 *     {@code quotes} and {@code provenance} saying where each comes from ({@link GroundedAnswer})
 * @param revision the release the answering airlock came from
 * @param traceId the exchange's trace; null when nothing traces it
 */
public record ChatAnswer(
    String answer,
    @JsonProperty("dont_know") boolean dontKnow,
    List<GroundedAnswer.Segment> segments,
    List<GroundedAnswer.Figure> figures,
    List<GroundedAnswer.Table> tables,
    List<GroundedAnswer.Quote> quotes,
    GroundedAnswer.Provenance provenance,
    List<EvidenceSummary> evidence,
    @JsonProperty("tool_errors") List<ToolErrorSummary> toolErrors,
    Verification verification,
    String revision,
    @JsonProperty("trace_id") String traceId) {

  /**
   * @param source what produced it: the tool, or the handler that added passages
   * @param name what it read: the entity, or again the handler for passages
   * @param rows the rows or passages it holds
   * @param dataSource the data source a tool read; null for passages
   */
  public record EvidenceSummary(
      String id,
      String source,
      String name,
      int rows,
      @JsonProperty("data_source") String dataSource) {}

  /**
   * A tool call that returned no evidence: {@code login_refused}, {@code unreachable} or {@code
   * failed}.
   */
  public record ToolErrorSummary(
      @JsonProperty("call_id") String callId, String tool, String error) {}

  /**
   * @param verified a verifier ran and every one passed; false without verifiers, since nothing
   *     checked the answer
   */
  public record Verification(boolean verified, List<AuditEvent.Verifier> verdicts) {}

  /**
   * @throws ExchangeFailedException the exchange ended in an {@code error} event: there is no
   *     answer to collect
   */
  static ChatAnswer from(List<ChatEvent> events, String revision, String traceId) {
    StringBuilder answer = new StringBuilder();
    List<EvidenceSummary> evidence = new ArrayList<>();
    List<ToolErrorSummary> toolErrors = new ArrayList<>();
    List<AuditEvent.Verifier> verdicts = new ArrayList<>();
    ChatEvent.Done done = null;
    for (ChatEvent event : events) {
      switch (event) {
        case ChatEvent.Evidence e ->
            evidence.add(
                new EvidenceSummary(e.id(), e.tool(), e.entity(), e.rowCount(), e.dataSource()));
        case ChatEvent.ToolError e ->
            toolErrors.add(new ToolErrorSummary(e.callId(), e.tool(), e.error()));
        case ChatEvent.AnswerChunk chunk -> answer.append(chunk.content());
        case ChatEvent.Verification v ->
            v.verdicts()
                .forEach(d -> verdicts.add(new AuditEvent.Verifier(d.handler(), d.status())));
        case ChatEvent.Error error -> throw new ExchangeFailedException(error);
        case ChatEvent.Done last -> done = last;
        case ChatEvent.ToolCall _ -> {}
      }
    }
    boolean verified =
        !verdicts.isEmpty() && verdicts.stream().allMatch(v -> v.verdict() == Status.PASS);
    GroundedAnswer grounded =
        done == null || done.provenance() == null
            ? GroundedAnswer.plain(answer.toString())
            : new GroundedAnswer(
                done.answer(),
                done.segments(),
                done.figures(),
                done.tables(),
                done.quotes(),
                done.provenance());
    return new ChatAnswer(
        answer.toString(),
        Answers.isDontKnow(answer.toString()),
        grounded.segments(),
        grounded.figures(),
        grounded.tables(),
        grounded.quotes(),
        grounded.provenance(),
        List.copyOf(evidence),
        List.copyOf(toolErrors),
        new Verification(verified, List.copyOf(verdicts)),
        revision,
        traceId);
  }
}
