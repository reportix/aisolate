import type { Cell, Figure, FigureStatus, Grounding, Quote, StreamEvent, Table } from "../types";

/** One figure per status, shaped as the airlock returns them. */
export const figures: Record<FigureStatus, Figure> = {
  sourced: {
    id: "f1",
    display: "78,000 EUR",
    value: "78000",
    status: "sourced",
    source: {
      evidence: "ev-1",
      entity: "hr.compensation",
      column: "gross_salary",
      row_key: "emp-17",
      subject: "Elena Varga",
      period: "2026",
      unit: "EUR",
    },
  },
  computed: {
    id: "f2",
    display: "12",
    value: "12",
    status: "computed",
    computed: {
      op: "subtract",
      of: [
        { evidence: "ev-2", column: "granted", value: "30" },
        { evidence: "ev-2", column: "taken", value: "18" },
      ],
    },
  },
  unsourced: { id: "u1", display: "15", status: "unsourced" },
  mismatch: {
    id: "f3",
    display: "4.2M EUR",
    value: "4200000",
    status: "mismatch",
    reason: "the row is about Pellworth Foods, not Northbeck",
    source: {
      evidence: "ev-3",
      entity: "finance.revenue",
      column: "revenue",
      subject: "Pellworth Foods",
    },
  },
};

/** Elena's answer: a sourced salary, computed leave, and a year and a carry-over the model typed. */
export const elena: Required<Grounding> = {
  segments: [
    { text: "Your gross salary for " },
    { figure: "u2" },
    { text: " is " },
    { figure: "f1" },
    { text: " a year. You have " },
    { figure: "f2" },
    { text: " days of leave left, and up to " },
    { figure: "u1" },
    { text: " may carry over into next year." },
  ],
  figures: [
    figures.sourced,
    figures.computed,
    figures.unsourced,
    // A year typed in the text, not in the question: a figure like any other.
    { id: "u2", display: "2026", status: "unsourced" },
  ],
  tables: [],
  quotes: [],
  provenance: { status: "has_unsourced", sourced: 1, computed: 1, unsourced: 2, mismatch: 0 },
};

const cell = (display: string, column: string, subject: string, value?: string): Cell => ({
  display,
  value,
  status: "sourced",
  source: { evidence: "ev-4", entity: "ledger.income", column, subject, period: "2025" },
});

/** The two customers with the most income: a ranked table of one evidence. */
export const topTwo: Table = {
  id: "t1",
  status: "sourced",
  evidence: "ev-4",
  entity: "ledger.income",
  call: { tool: "read_ledger", args: { order: "income desc", limit: 2 } },
  columns: ["customer", "income"],
  rows: [
    [
      cell("Vossberg Retail AG", "customer", "Vossberg Retail AG"),
      cell("1,284,500 EUR", "income", "Vossberg Retail AG", "1284500"),
    ],
    [
      cell("Hafenstern Beverages GmbH", "customer", "Hafenstern Beverages GmbH"),
      cell("968,200 EUR", "income", "Hafenstern Beverages GmbH", "968200"),
    ],
  ],
};

/** One quote per status, from the same passage. */
export const quotes: Record<Quote["status"], Quote> = {
  quoted: {
    id: "q1",
    status: "quoted",
    evidence: "ev-5",
    index: 2,
    title: "Engagement letter",
    section: "Related parties",
    text: "provide a complete list of related parties",
  },
  mismatch: {
    id: "q2",
    status: "mismatch",
    evidence: "ev-5",
    index: 2,
    title: "Engagement letter",
    section: "Related parties",
    text: "disclose nothing further",
    reason: "the passage does not hold these words",
  },
};

export const elenaDone: StreamEvent = {
  type: "done",
  evidence_ids: ["ev-1", "ev-2"],
  grounded: true,
  ...elena,
};
