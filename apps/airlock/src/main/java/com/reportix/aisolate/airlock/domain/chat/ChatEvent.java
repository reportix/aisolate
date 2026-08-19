package com.reportix.aisolate.airlock.domain.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.reportix.aisolate.sdk.Verdict;
import java.util.List;
import java.util.Map;

/** One server-sent event of a chat exchange. Serialised with a {@code type} discriminator. */
public sealed interface ChatEvent {

  /** The SSE event name, also written as the {@code type} field of the JSON data. */
  @JsonProperty("type")
  default String type() {
    return switch (this) {
      case ToolCall _ -> "tool_call";
      case ToolError _ -> "tool_error";
      case Evidence _ -> "evidence";
      case AnswerChunk _ -> "answer_chunk";
      case Verification _ -> "verification";
      case Done _ -> "done";
      case Error _ -> "error";
    };
  }

  /**
   * {@code id} is numbered by the exchange ({@code call-1}, {@code call-2}, ...); the matching
   * evidence quotes it back as {@code call_id}.
   */
  record ToolCall(String id, String tool, Map<String, Object> args) implements ChatEvent {}

  /**
   * A tool call that returned no evidence, and why in one word: {@code login_refused} or {@code
   * unreachable} (its data source) or {@code failed} (anything else). Never the tool server's text.
   *
   * @param dataSource the data source the tool server named, for the audit line alone
   */
  record ToolError(
      @JsonProperty("call_id") String callId,
      String tool,
      String error,
      @JsonIgnore String dataSource)
      implements ChatEvent {}

  /**
   * Provenance of one tool result. Rows never travel here.
   *
   * @param dataSource the data source the tool read; null for passages and tools that read none
   * @param rowsHash the hash of the rows, for the audit line alone ({@code ChatExchange#rowsHash}):
   *     the browser never gets it; null for passages
   */
  record Evidence(
      String id,
      @JsonProperty("call_id") String callId,
      String tool,
      String entity,
      @JsonProperty("row_count") int rowCount,
      List<String> columns,
      @JsonProperty("rule_version") String ruleVersion,
      List<String> rules,
      @JsonProperty("sql_hash") String sqlHash,
      @JsonProperty("data_source") String dataSource,
      @JsonIgnore String rowsHash)
      implements ChatEvent {}

  record AnswerChunk(String content) implements ChatEvent {}

  /** The verifiers' word on the answer; sent after the answer, before done, only when one ran. */
  record Verification(List<Verdict> verdicts) implements ChatEvent {}

  /**
   * The last event: the answer once more, now with where each figure comes from ({@link
   * GroundedAnswer}).
   *
   * @param grounded the exchange gathered evidence
   */
  record Done(
      @JsonProperty("evidence_ids") List<String> evidenceIds,
      boolean grounded,
      String answer,
      List<GroundedAnswer.Segment> segments,
      List<GroundedAnswer.Figure> figures,
      List<GroundedAnswer.Table> tables,
      List<GroundedAnswer.Quote> quotes,
      GroundedAnswer.Provenance provenance)
      implements ChatEvent {

    static Done of(List<String> evidenceIds, boolean grounded, GroundedAnswer answer) {
      return new Done(
          evidenceIds,
          grounded,
          answer.answer(),
          answer.segments(),
          answer.figures(),
          answer.tables(),
          answer.quotes(),
          answer.provenance());
    }
  }

  /** The fixed sentences a browser may see; causes stay in the server log. */
  record Error(String message) implements ChatEvent {

    /** Any failure whose cause the browser must not learn. */
    public static final Error COULD_NOT_COMPLETE =
        new Error("The assistant could not complete the request");

    /** The airlock names a model this AIsolate has no provider client for. */
    public static final Error NO_MODEL_ENDPOINT =
        new Error("The airlock's model endpoint is not available");

    /** The model server refused, timed out or could not be reached: retry later, nothing else. */
    public static final Error MODEL_UNAVAILABLE =
        new Error("The model is not available right now. Please try again in a moment.");
  }
}
