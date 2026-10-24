import { describe, expect, it } from "vitest";
import { reduce } from "../events";
import { parseSse } from "../sse";
import type { Evidence, Message, StreamEvent } from "../types";
import { elena, elenaDone } from "./grounding.fixture";

const streaming = (): Message => ({
  id: "a1",
  role: "assistant",
  content: "",
  toolCalls: [],
  evidence: [],
  status: "streaming",
});

const ev1: Evidence = {
  id: "ev-1",
  call_id: "call-0",
  tool: "read_records",
  entity: "hr.compensation",
  row_count: 1,
  columns: ["full_name", "gross_salary"],
  rule_version: "1",
  rules: ["own-record"],
  sql_hash: "a1b2c3",
};

describe("reduce", () => {
  it("givenStreamingMessage_whenToolCall_thenAppendedAsPending", () => {
    // Arrange
    const msg = streaming();
    // Act
    const next = reduce(msg, {
      type: "tool_call",
      id: "call-0",
      tool: "read_records",
      args: { limit: 5 },
    });
    // Assert
    expect(next.toolCalls).toEqual([
      { id: "call-0", tool: "read_records", args: { limit: 5 }, done: false },
    ]);
    expect(msg.toolCalls).toEqual([]);
  });

  it("givenPendingToolCall_whenEvidence_thenCallDoneWithItsEntityAndEvidenceAppended", () => {
    // Arrange
    const msg = reduce(streaming(), {
      type: "tool_call",
      id: "call-0",
      tool: "read_records",
      args: {},
    });
    // Act
    const next = reduce(msg, { type: "evidence", ...ev1 });
    // Assert
    expect(next.toolCalls[0].done).toBe(true);
    expect(next.toolCalls[0].entity).toBe("hr.compensation");
    expect(next.evidence).toEqual([ev1]);
  });

  it.each(["login_refused", "unreachable", "failed"] as const)(
    "givenPendingToolCall_whenToolError_%s_thenCallDoneWithThatError",
    (error) => {
      // Arrange
      const msg = reduce(streaming(), {
        type: "tool_call",
        id: "call-0",
        tool: "read_records",
        args: {},
      });
      // Act
      const next = reduce(msg, {
        type: "tool_error",
        call_id: "call-0",
        tool: "read_records",
        error,
      });
      // Assert
      expect(next.toolCalls[0]).toMatchObject({ done: true, error });
      expect(next.evidence).toEqual([]);
    },
  );

  it("givenStreamingMessage_whenVerification_thenVerdictsKeptOnTheMessage", () => {
    // Arrange
    const msg = streaming();
    // Act
    const next = reduce(msg, {
      type: "verification",
      verdicts: [{ handler: "SalaryVerifier", status: "PASS" }],
    });
    // Assert
    expect(next.verdicts).toEqual([{ handler: "SalaryVerifier", status: "PASS" }]);
    expect(next.status).toBe("streaming");
  });

  it("givenAnswerChunk_whenAnotherChunk_thenConcatenated", () => {
    // Arrange
    const msg = reduce(streaming(), { type: "answer_chunk", content: "Hello" });
    // Act
    const next = reduce(msg, { type: "answer_chunk", content: " world" });
    // Assert
    expect(next.content).toBe("Hello world");
  });

  it("givenStreamingMessage_whenDone_thenFinishedWithTheGroundedFlag", () => {
    // Arrange
    const msg = streaming();
    // Act
    const next = reduce(msg, {
      type: "done",
      evidence_ids: ["ev-1"],
      grounded: true,
    });
    // Assert
    expect(next.status).toBe("done");
    expect(next.grounded).toBe(true);
  });

  it("givenTheFinalEventWithSegments_whenParsedAndReduced_thenTheAnswerCarriesItsFigures", async () => {
    // Arrange
    const body = new Response(`data: ${JSON.stringify(elenaDone)}\n\n`).body!;
    const events: StreamEvent[] = [];

    // Act
    await parseSse(body, (e) => events.push(e));
    const next = events.reduce(reduce, streaming());

    // Assert
    expect(next.status).toBe("done");
    expect(next.segments).toEqual(elena.segments);
    expect(next.figures).toEqual(elena.figures);
    expect(next.provenance).toEqual(elena.provenance);
  });

  it("givenTheFinalEventOfAnOlderAirlock_whenReduced_thenNoSegmentsSoTheTextRendersAsBefore", () => {
    // Arrange
    const msg = reduce(streaming(), { type: "answer_chunk", content: "Your salary is 78,000." });

    // Act
    const next = reduce(msg, { type: "done", evidence_ids: [], grounded: true });

    // Assert
    expect(next).not.toHaveProperty("segments");
    expect(next.content).toBe("Your salary is 78,000.");
  });

  it("givenStreamingMessage_whenError_thenStatusAndMessageRecorded", () => {
    // Arrange
    const msg = streaming();
    // Act
    const next = reduce(msg, { type: "error", message: "boom" });
    // Assert
    expect(next.status).toBe("error");
    expect(next.error).toBe("boom");
  });

  it("givenTwoCallsToOneTool_whenEvidenceForTheSecond_thenPairedWithTheCallItAnswers", () => {
    // Arrange: the same tool is called twice in one turn
    const first = reduce(streaming(), {
      type: "tool_call",
      id: "call-0",
      tool: "read_records",
      args: { limit: 1 },
    });
    const msg = reduce(first, {
      type: "tool_call",
      id: "call-1",
      tool: "read_records",
      args: { limit: 2 },
    });

    // Act: the evidence answers the second call
    const next = reduce(msg, { type: "evidence", ...ev1, id: "ev-2", call_id: "call-1" });

    // Assert
    expect(next.toolCalls[0].done).toBe(false);
    expect(next.toolCalls[1].done).toBe(true);
  });

  it("givenUnknownEventType_whenReduced_thenMessageUnchanged", () => {
    // Arrange
    const msg = streaming();

    // Act
    const next = reduce(msg, { type: "something_new" } as never);

    // Assert
    expect(next).toBe(msg);
  });

  it("givenEvidenceWithRowsAndLabels_whenReduced_thenTheyStayOnTheEvidence", () => {
    // Arrange
    const rows = [{ full_name: "Elena Varga", gross_salary: 78000 }];
    // Act
    const next = reduce(streaming(), {
      type: "evidence",
      ...ev1,
      labels: { row_key: "full_name" },
      rows,
    });
    // Assert
    expect(next.evidence[0]).toMatchObject({ labels: { row_key: "full_name" }, rows });
  });

  it("givenPassagesEvidence_whenReduced_thenThePassagesStayOnTheEvidence", () => {
    // Arrange
    const passages = [{ title: "Handbook", section: "Notice", body: "Three months." }];
    // Act
    const next = reduce(streaming(), { type: "evidence", ...ev1, id: "ev-2", passages });
    // Assert
    expect(next.evidence[0].passages).toEqual(passages);
  });
});
