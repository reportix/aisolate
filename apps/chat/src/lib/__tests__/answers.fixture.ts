import type { Evidence, Message, ToolCallInfo } from "../types";

// Whole answers as the airlock streams them (docs/verification.md at the repository root, the golden
// set's shapes), with the demo cast's data: one per kind of provenance the chat shows.

const call = (id: string, tool: string, args: Record<string, unknown>): ToolCallInfo => ({
  id,
  tool,
  args,
  done: true,
  entity: typeof args.entity === "string" ? args.entity : undefined,
});

const rows = (id: string, c: ToolCallInfo, row_count: number, columns: string[]): Evidence => ({
  id,
  call_id: c.id,
  tool: c.tool,
  entity: c.entity ?? "",
  row_count,
  columns,
  rule_version: "1.4c1e0b2a",
  rules: ["own-record"],
  sql_hash: "9f2c41d0",
});

const answer = (question: string, m: Omit<Message, "id" | "role" | "status">): Message[] => [
  {
    id: `u-${question}`,
    role: "user",
    content: question,
    toolCalls: [],
    evidence: [],
    status: "done",
  },
  { id: `a-${question}`, role: "assistant", status: "done", ...m },
];

const compensation = call("call-1", "read_my_records", { entity: "hr.compensation" });

/** Elena's salary: one figure, copied from her own row. */
export const elenaSalary = answer("What is my gross salary?", {
  content: "Your gross salary is 78,000 EUR.",
  toolCalls: [compensation],
  evidence: [
    rows("ev-1", compensation, 1, ["self", "employee_id", "full_name", "gross_salary", "currency"]),
  ],
  grounded: true,
  segments: [{ text: "Your gross salary is " }, { figure: "f1" }, { text: "." }],
  figures: [
    {
      id: "f1",
      display: "78,000 EUR",
      value: "78000",
      status: "sourced",
      source: {
        evidence: "ev-1",
        entity: "hr.compensation",
        column: "gross_salary",
        row_key: "17",
        subject: "Elena Varga",
        unit: "EUR",
      },
    },
  ],
  tables: [],
  quotes: [],
  provenance: { status: "all_sourced", sourced: 1, computed: 0, unsourced: 0, mismatch: 0 },
});

const overdue = call("call-1", "read_records", {
  entity: "clients.invoices",
  filters: [{ column: "status", op: "eq", value: "overdue" }],
  limit: 20,
});
const invoice = (client: string, number: string, amount: string, due: string) => {
  const at = { evidence: "ev-1", entity: "clients.invoices", row_key: number, subject: client };
  return [
    {
      display: client,
      value: client,
      status: "sourced" as const,
      source: { ...at, column: "client" },
    },
    {
      display: number,
      value: number,
      status: "sourced" as const,
      source: { ...at, column: "number" },
    },
    {
      display: `${amount} EUR`,
      value: amount.replace(",", ""),
      status: "sourced" as const,
      source: { ...at, column: "amount_eur", unit: "EUR" },
    },
    { display: due, value: due, status: "sourced" as const, source: { ...at, column: "due_on" } },
  ];
};
const invoices = [
  invoice("Contoso Exchange Group", "INV-031", "45,000", "2026-09-07"),
  invoice("Fabrikam Energie AG", "INV-038", "27,500", "2026-09-22"),
  invoice("Proseware Chemie GmbH", "INV-044", "17,500", "2026-09-23"),
];

/** Mark's overdue invoices: a table of one tool result and the total AIsolate added up. */
export const markOverdue = answer(
  "Which invoices are overdue, and what do they come to in total?",
  {
    content: "",
    toolCalls: [overdue],
    evidence: [rows("ev-1", overdue, 3, ["client", "number", "amount_eur", "due_on", "status"])],
    grounded: true,
    segments: [
      { text: "These invoices are overdue, " },
      { figure: "f1" },
      { text: " in all:\n\n" },
      { table: "t1" },
    ],
    figures: [
      {
        id: "f1",
        display: "90,000 EUR",
        value: "90000",
        status: "computed",
        computed: {
          op: "sum",
          of: invoices.map(([, , amount]) => ({
            ...amount.source,
            value: amount.value,
            display: amount.display,
          })),
        },
      },
    ],
    tables: [
      {
        id: "t1",
        status: "sourced",
        evidence: "ev-1",
        entity: "clients.invoices",
        call: { tool: overdue.tool, args: overdue.args },
        columns: ["client", "number", "amount_eur", "due_on"],
        rows: invoices,
      },
    ],
    quotes: [],
    provenance: { status: "all_sourced", sourced: 1, computed: 1, unsourced: 0, mismatch: 0 },
  },
);

const documents: Evidence = {
  id: "ev-1",
  call_id: "",
  tool: "sales-documents",
  entity: "sales-documents",
  row_count: 1,
  columns: ["title", "section"],
  rule_version: "",
  rules: [],
  sql_hash: "",
};
const cap =
  "Price increases are capped at 3 percent per contract year through 2028 and need three months' written notice.";

/** Northwind's price cap: a passage's own words, written into the text by the airlock. */
export const northwind = answer("What did we agree with Northwind Bank on the price cap?", {
  content: "",
  toolCalls: [],
  evidence: [documents],
  grounded: true,
  segments: [{ text: `The agreement with Northwind Bank AG says: "${cap}"` }],
  figures: [],
  tables: [],
  quotes: [
    {
      id: "q1",
      status: "quoted",
      evidence: "ev-1",
      index: 1,
      title: "Master agreement Northwind Bank AG",
      section: "Pricing",
      text: cap,
    },
  ],
  provenance: { status: "no_figures", sourced: 0, computed: 0, unsourced: 0, mismatch: 0 },
});

const average = call("call-2", "aggregate_records", {
  entity: "hr.compensation",
  function: "avg",
  column: "gross_salary",
  group_by: "department",
  filters: [{ column: "department", op: "eq", value: "Sales" }],
});

/** Mark's department average: a group's value from an aggregate, beside his own salary. */
export const markAverage = answer("How does my salary compare with my department's average?", {
  content: "",
  toolCalls: [compensation, average],
  evidence: [
    rows("ev-1", compensation, 1, [
      "self",
      "employee_id",
      "full_name",
      "department",
      "gross_salary",
      "currency",
    ]),
    rows("ev-2", average, 1, ["department", "value", "n"]),
  ],
  grounded: true,
  segments: [
    { text: "The average gross salary in Sales is " },
    { figure: "f1" },
    { text: " across " },
    { figure: "f2" },
    { text: " people. Yours is " },
    { figure: "f3" },
    { text: ", " },
    { figure: "f4" },
    { text: " above it." },
  ],
  figures: [
    {
      id: "f1",
      display: "64,250",
      value: "64250",
      status: "sourced",
      source: { evidence: "ev-2", entity: "hr.compensation", column: "value", row_key: "Sales" },
    },
    {
      id: "f2",
      display: "4",
      value: "4",
      status: "sourced",
      source: { evidence: "ev-2", entity: "hr.compensation", column: "n", row_key: "Sales" },
    },
    {
      id: "f3",
      display: "72,000 EUR",
      value: "72000",
      status: "sourced",
      source: {
        evidence: "ev-1",
        entity: "hr.compensation",
        column: "gross_salary",
        row_key: "23",
        subject: "Mark Hollander",
        unit: "EUR",
      },
    },
    {
      id: "f4",
      display: "7,750",
      value: "7750",
      status: "computed",
      computed: {
        op: "subtract",
        of: [
          {
            evidence: "ev-1",
            entity: "hr.compensation",
            column: "gross_salary",
            subject: "Mark Hollander",
            value: "72000",
            display: "72,000",
          },
          {
            evidence: "ev-2",
            entity: "hr.compensation",
            column: "value",
            row_key: "Sales",
            value: "64250",
            display: "64,250",
          },
        ],
      },
    },
  ],
  tables: [],
  quotes: [],
  provenance: { status: "all_sourced", sourced: 3, computed: 1, unsourced: 0, mismatch: 0 },
});

const leave = call("call-1", "read_my_records", { entity: "hr.leave" });

/** Elena's leave: a sourced balance, and a carry-over limit the model typed from nowhere. */
export const unsourced = answer("How many vacation days do I have left?", {
  content: "",
  toolCalls: [leave],
  evidence: [
    rows("ev-1", leave, 2, ["self", "employee_id", "full_name", "year", "remaining_days"]),
  ],
  grounded: true,
  segments: [
    { text: "You have " },
    { figure: "f1" },
    { text: " left this year, and up to " },
    { figure: "u1" },
    { text: " may carry over into next year." },
  ],
  figures: [
    {
      id: "f1",
      display: "12 days",
      value: "12",
      status: "sourced",
      source: {
        evidence: "ev-1",
        entity: "hr.leave",
        column: "remaining_days",
        row_key: "17",
        subject: "Elena Varga",
        period: "2026",
        unit: "days",
      },
    },
    { id: "u1", display: "5", status: "unsourced", reason: "typed in the text, not referenced" },
  ],
  tables: [],
  quotes: [],
  provenance: { status: "has_unsourced", sourced: 1, computed: 0, unsourced: 1, mismatch: 0 },
});

const open = call("call-1", "read_records", {
  entity: "clients.invoices",
  filters: [
    { column: "status", op: "eq", value: "overdue" },
    { column: "amount_eur", op: "gte", value: 10000 },
  ],
});

/** Northwind's debt cited from Fabrikam's row: the amount is a mismatch, its due date sourced. */
export const mismatch = answer("What does Northwind Bank owe us, and by when?", {
  content: "",
  toolCalls: [open],
  evidence: [rows("ev-1", open, 2, ["client", "number", "amount_eur", "due_on", "status"])],
  grounded: true,
  segments: [
    { text: "Northwind Bank AG owes " },
    { figure: "f1" },
    { text: ", due on " },
    { figure: "f2" },
    { text: "." },
  ],
  figures: [
    {
      id: "f1",
      display: "27,500 EUR",
      value: "27500",
      status: "mismatch",
      reason: "the row is about Fabrikam Energie AG, not Northwind Bank AG",
      source: {
        evidence: "ev-1",
        entity: "clients.invoices",
        column: "amount_eur",
        row_key: "INV-038",
        subject: "Fabrikam Energie AG",
        unit: "EUR",
      },
    },
    {
      id: "f2",
      display: "2026-10-02",
      value: "2026-10-02",
      status: "sourced",
      source: {
        evidence: "ev-1",
        entity: "clients.invoices",
        column: "due_on",
        row_key: "INV-047",
        subject: "Northwind Bank AG",
      },
    },
  ],
  tables: [],
  quotes: [],
  provenance: { status: "has_mismatch", sourced: 1, computed: 0, unsourced: 0, mismatch: 1 },
});

const someoneElse = call("call-1", "read_records", {
  entity: "hr.compensation",
  filters: [{ column: "full_name", op: "eq", value: "Mark Hollander" }],
});

/** Another person's salary: the rules hold back every row, so the answer is exactly "I don't know". */
export const dontKnow = answer("What is Mark Hollander's salary?", {
  content: "I don't know",
  toolCalls: [someoneElse],
  evidence: [rows("ev-1", someoneElse, 0, ["full_name", "gross_salary"])],
  grounded: true,
  segments: [{ text: "I don't know" }],
  figures: [],
  tables: [],
  quotes: [],
  provenance: { status: "no_figures", sourced: 0, computed: 0, unsourced: 0, mismatch: 0 },
});
