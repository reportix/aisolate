import { describe, expect, it } from "vitest";
import type { AuditLine } from "../audit";
import {
  type Step,
  detailsOf,
  duration,
  rowsOf,
  spansMessage,
  stepsLabel,
  traceFiltersOf,
  tracesQuery,
} from "../trace";

const step = (
  id: string,
  name: string,
  kind: string,
  start: number,
  children: Step[] = [],
  attributes: Record<string, unknown> = {},
): Step => ({
  id,
  name,
  kind,
  service: "aisolate-airlock",
  start_us: start,
  duration_us: 100,
  error: false,
  error_type: null,
  attributes,
  children,
});

// As the backend maps a Java airlock's spans: HTTP around the exchange, Spring AI's wrappers around
// the model calls and the tool call, the tool server's endpoint between the call and its query.
const java: Step[] = [
  step("http", "http post /v1/spaces/{spaceId}/airlocks/{airlockId}/chat", "http", 0, [
    step("ex", "exchange acme/hr", "exchange", 10, [
      step("adm", "admission", "admission", 20),
      step(
        "read",
        "Read HR documents",
        "handler",
        30,
        [step("emb", "embedding", "embedding", 35, [], { "aisolate.llm": "nomic" })],
        {
          "aisolate.handler": "HrDocuments",
          "aisolate.data_source": "hr",
        },
      ),
      step("cc", "spring_ai chat_client", "other", 40, [
        step("c1", "chat granite4.1:8b", "model", 50, [step("h1", "http post", "http", 51)], {
          "aisolate.llm": "granite",
        }),
        step(
          "t1",
          "tool read_records",
          "tool",
          60,
          [
            step("mcp", "http post /mcp", "mcp", 61, [
              step(
                "q1",
                "governed query read_records",
                "query",
                62,
                [step("s1", "sql hr", "sql", 63, [], { "aisolate.data_source": "hr" })],
                {
                  "aisolate.entity": "hr.compensation",
                },
              ),
            ]),
          ],
          { "aisolate.tool": "read_records", "aisolate.rows": 1 },
        ),
        step("c2", "chat granite4.1:8b", "model", 70, [], { "aisolate.llm": "granite" }),
      ]),
    ]),
  ]),
];

const event = {
  space: "acme",
  airlock: "hr",
  revision: "594b56a06935",
  outcome: "answered",
  reason: null,
  tools: [
    {
      name: "read_records",
      args: { entity: "hr.compensation", limit: 20 },
      data_source: "hr",
      rows: 1,
      rule_version: null,
      sql_hash: null,
      rows_hash: null,
    },
  ],
} as unknown as AuditLine;

describe("rowsOf", () => {
  it("givenAJavaTrace_whenRowsBuilt_thenTransportAndWrappersLeftOutAndModelCallsNumbered", () => {
    // Arrange / Act
    const rows = rowsOf(java);

    // Assert
    expect(rows.map((r) => [r.depth, r.label, r.look])).toEqual([
      [0, "Exchange acme/hr", "step"],
      [1, "Admission", "step"],
      [1, "Read HR documents", "data"],
      [2, "Embedding · nomic", "model"],
      [1, "Model call 1 · granite", "model"],
      [1, "Tool read_records", "tool"],
      [2, "Governed query", "tool"],
      [3, "SQL on hr", "data"],
      [1, "Model call 2 · granite", "model"],
    ]);
  });
});

describe("detailsOf", () => {
  it("givenAToolCall_whenDetailed_thenItsArgumentsAndDataSourceFromTheAuditAndItsEntityFromItsQuery", () => {
    // Arrange
    const rows = rowsOf(java);
    const tool = rows.findIndex((r) => r.label === "Tool read_records");

    // Act
    const details = detailsOf(rows, tool, event);

    // Assert
    expect(details.kind).toBe("Tool call");
    expect(details.facts).toEqual([
      ["Tool", "read_records"],
      ["Data source", "hr"],
      ["Entity", "hr.compensation"],
      ["Rows returned", "1"],
    ]);
    expect(JSON.parse(details.args!)).toEqual({ entity: "hr.compensation", limit: 20 });
  });

  it("givenTheExchange_whenDetailed_thenItsQuestionAndAnswerAsJsonFromTheAudit", () => {
    // Arrange
    const rows = rowsOf(java);
    const asked = {
      ...event,
      question: "What is my salary?",
      answer: "I don't know",
      outcome: "dont_know" as const,
    };

    // Act
    const { io } = detailsOf(rows, 0, asked);

    // Assert
    expect(JSON.parse(io!.inputs)).toEqual({ question: "What is my salary?" });
    expect(JSON.parse(io!.outputs)).toMatchObject({ answer: "I don't know", outcome: "dont_know" });
  });

  it.each([
    ["Model call 1 · granite", "asks for read_records"],
    ["Model call 2 · granite", "the answer"],
  ])("givenTheModelCall_%s_whenDetailed_thenItsResultIs_%s", (label, result) => {
    // Arrange
    const rows = rowsOf(java);

    // Act
    const details = detailsOf(
      rows,
      rows.findIndex((r) => r.label === label),
      event,
    );

    // Assert
    expect(details.facts).toContainEqual(["Result", result]);
  });
});

describe("spansMessage", () => {
  it.each([
    ["held", null],
    [
      "gone",
      "The trace store no longer holds this trace's steps: it keeps traces for a while only. The audit record is kept.",
    ],
    ["unavailable", "The trace store did not answer. Try again in a moment."],
  ] as const)("givenTheSpans_%s_whenShown_thenThePlainSentence", (status, message) => {
    // Arrange / Act
    const said = spansMessage(status);

    // Assert
    expect(said).toBe(message);
  });
});

describe("stepsLabel", () => {
  it.each([
    [{ event: "exchange", model_calls: 4, tool_calls: 3 }, "4 model calls · 3 tools"],
    [{ event: "exchange", model_calls: 1, tool_calls: 1 }, "1 model call · 1 tool"],
    [{ event: "refusal", model_calls: null, tool_calls: null }, "admission only"],
  ] as const)("givenTheCounts_%o_whenLabelled_then_%s", (t, label) => {
    // Arrange / Act
    const said = stepsLabel(t);

    // Assert
    expect(said).toBe(label);
  });
});

describe("duration", () => {
  it.each([
    [8_565_600, "8.57 s"],
    [9_500, "9.5 ms"],
    [3_000, "3 ms"],
    [40, "40 µs"],
  ])("givenMicroseconds_%d_whenShown_then_%s", (us, text) => {
    // Arrange / Act
    const said = duration(us);

    // Assert
    expect(said).toBe(text);
  });
});

describe("traceFiltersOf", () => {
  it.each([
    ["NoPeriod", "", "24h"],
    ["AKnownPeriod", "?period=7d", "7d"],
    ["AnUnknownPeriod", "?period=2y", "24h"],
    ["AnObjectsOwnName_constructor", "?period=constructor", "24h"],
    ["AnObjectsOwnName_toString", "?period=toString", "24h"],
  ])("givenTheAddress_%s_whenRead_thenItsPeriodOrTheLast24Hours", (_, search, period) => {
    // Arrange / Act
    const read = traceFiltersOf(search);

    // Assert
    expect(read.period).toBe(period);
  });
});

describe("tracesQuery", () => {
  it.each([
    [{ period: "24h" }, "?from=2026-10-18T10%3A00%3A00.000Z"],
    [{ space: "acme", q: " salary ", period: "all" }, "?space=acme&q=salary"],
  ] as const)("givenTheFilters_%o_whenQueried_then_%s", (filters, query) => {
    // Arrange
    const now = Date.parse("2026-10-19T10:00:00Z");

    // Act
    const q = tracesQuery(filters, now);

    // Assert
    expect(q).toBe(query);
  });
});
