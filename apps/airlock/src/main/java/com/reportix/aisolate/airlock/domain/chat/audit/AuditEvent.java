package com.reportix.aisolate.airlock.domain.chat.audit;

import com.fasterxml.jackson.annotation.JsonValue;
import com.reportix.aisolate.sdk.Verdict;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One line of the audit trail: who asked what of which airlock, what came of it and what it cost.
 * Written once per exchange and once per refused request. It holds provenance, never rows. Every
 * field is described once, in docs/security.md, Audit trail.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AuditEvent(
    Instant ts,
    Event event,
    String traceId,
    String space,
    String airlock,
    String revision,
    String user,
    String client,
    String question,
    String answer,
    Outcome outcome,
    String reason,
    List<Tool> tools,
    List<String> evidence,
    List<Verifier> verifiers,
    Figures figures,
    Map<String, Object> grounding,
    Model model,
    long durationMs) {

  public enum Event {
    EXCHANGE,
    REFUSAL;

    @JsonValue
    public String json() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum Outcome {
    ANSWERED,
    DONT_KNOW,
    REFUSED,
    ERROR;

    @JsonValue
    public String json() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * @param args the arguments the tool server ran the call with, so it can be re-run at the same
   *     revision; null when the call failed, since an argument it refused (a filter on a hidden or
   *     masked column among them) is never recorded
   * @param ruleVersion the rule version the tool server applied
   * @param sqlHash the shape of the statement it ran
   * @param rowsHash sha256 of the rows it returned, canonical: a re-run of the same query at the
   *     same revision gives the same hash, unless the data changed
   * @param error null when the call returned evidence; else {@code login_refused}, {@code
   *     unreachable} or {@code failed}
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Tool(
      String name,
      Map<String, Object> args,
      String dataSource,
      Integer rows,
      String ruleVersion,
      String sqlHash,
      String rowsHash,
      String error) {}

  /**
   * The answer's figures by provenance (docs/verification.md): counts, never a value. {@code
   * grounding} beside it holds the answer's segments, figures, tables and quotes as the chat
   * returned them, less what the cells held ({@code value}, {@code subject}, {@code period}, {@code
   * unit}), so the audit shows the answer with where each figure came from.
   */
  public record Figures(int sourced, int computed, int unsourced, int mismatch) {}

  public record Verifier(String name, Verdict.Status verdict) {}

  /**
   * @param provider the provider id the airlock names its model by
   * @param calls model calls the exchange made, a failed one included
   * @param inputTokens summed over the calls; null when the provider reported none
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Model(
      String provider, String name, int calls, Integer inputTokens, Integer outputTokens) {}

  public AuditEvent {
    ts = ts.truncatedTo(ChronoUnit.MILLIS);
    tools = List.copyOf(tools);
    evidence = List.copyOf(evidence);
    verifiers = List.copyOf(verifiers);
  }

  /**
   * A request the airlock turned away before any exchange: nothing ran, nothing was answered;
   * {@code reason} is {@code role} or {@code client}.
   */
  public static AuditEvent refusal(
      Instant ts,
      String traceId,
      String space,
      String airlock,
      String revision,
      String user,
      String client,
      String question,
      String reason,
      long durationMs) {
    return new AuditEvent(
        ts,
        Event.REFUSAL,
        traceId,
        space,
        airlock,
        revision,
        user,
        client,
        question,
        null,
        Outcome.REFUSED,
        reason,
        List.of(),
        List.of(),
        List.of(),
        null,
        null,
        null,
        durationMs);
  }
}
