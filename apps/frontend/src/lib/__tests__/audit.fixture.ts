import type { AuditDetail, AuditSummary, ChainStatus } from "../audit";

/** Elena asks the Acme HR airlock for her salary; shaped as the audit endpoints answer. */
export const summary: AuditSummary = {
  id: 1042,
  received_at: "2026-10-18T09:12:03.120Z",
  ts: "2026-10-18T09:11:57.006Z",
  event: "exchange",
  space: "acme",
  airlock: "hr",
  revision: "7b2280eab4b5",
  trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
  user: "elena@example.com",
  client: "acme-chat",
  outcome: "answered",
  reason: null,
  question: "What is my gross salary?",
  figures: { sourced: 1, computed: 0, unsourced: 1, mismatch: 0 },
  input_tokens: 1840,
  output_tokens: 212,
  duration_ms: 6114,
};

export const detail: AuditDetail = {
  id: 1042,
  received_at: summary.received_at,
  prev_hash: "9c1d".padEnd(64, "0"),
  hash: "e3b0".padEnd(64, "1"),
  event: {
    ts: summary.ts,
    event: "exchange",
    trace_id: summary.trace_id,
    space: "acme",
    airlock: "hr",
    revision: "7b2280eab4b5",
    user: "elena@example.com",
    client: "acme-chat",
    question: "What is my gross salary?",
    answer: "Your gross salary is 78,000 EUR, and 2026 is the year.",
    outcome: "answered",
    reason: null,
    tools: [
      {
        name: "read_my_records",
        args: { entity: "hr.compensation" },
        data_source: "hr",
        rows: 1,
        rule_version: "1.7b2280eab4b5",
        sql_hash: "3f9a1c0d2b7e",
        rows_hash: "5c1e9b0a4d7f2e8c3b6a1d0f9e8c7b6a5d4c3b2a1f0e9d8c7b6a5f4e3d2c1b0a",
      },
    ],
    evidence: ["ev-1"],
    verifiers: [{ name: "FigureVerifier", verdict: "PASS" }],
    figures: { sourced: 1, computed: 0, unsourced: 1, mismatch: 0 },
    grounding: {
      segments: [
        { text: "Your gross salary is " },
        { figure: "f1" },
        { text: ', as the policy says "paid in twelve parts". The year is ' },
        { figure: "u1" },
        { text: "." },
        { table: "t1" },
      ],
      figures: [
        {
          id: "f1",
          display: "78,000 EUR",
          status: "sourced",
          source: {
            evidence: "ev-1",
            entity: "hr.compensation",
            column: "gross_salary",
            row_key: "3",
          },
        },
        { id: "u1", display: "2026", status: "unsourced" },
      ],
      tables: [
        {
          id: "t1",
          status: "sourced",
          evidence: "ev-1",
          entity: "hr.compensation",
          call: { tool: "read_my_records", args: { entity: "hr.compensation" } },
          columns: ["gross_salary"],
          rows: [
            [
              {
                display: "78,000 EUR",
                status: "sourced",
                source: { evidence: "ev-1", entity: "hr.compensation", column: "gross_salary" },
              },
            ],
          ],
        },
      ],
      quotes: [
        {
          id: "q1",
          status: "quoted",
          evidence: "ev-2",
          index: 1,
          title: "Pay policy",
          section: "Salary",
          text: "paid in twelve parts",
        },
      ],
    },
    model: {
      provider: "ollama",
      name: "qwen3.6:27b-q4_K_M",
      calls: 2,
      input_tokens: 1840,
      output_tokens: 212,
    },
    duration_ms: 6114,
  },
};

export const intact: ChainStatus = {
  space: "acme",
  airlock: "hr",
  status: "ok",
  events: 1042,
  first_id: 1,
  last_id: 1042,
  head: detail.hash,
  broken: null,
};

export const broken: ChainStatus = {
  ...intact,
  status: "broken",
  broken: { id: 517, reason: "altered" },
};
