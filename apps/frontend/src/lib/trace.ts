import { type AuditLine, type Outcome, outcomeLabel } from "./audit";
import { PERIODS, type Period, periodOf } from "./period";
import { number } from "./text";

// The shapes of the backend's trace endpoints.

/** One exchange of Monitoring's list: its audit line's summary, with its calls counted. */
export type TraceSummary = {
  id: number;
  ts: string;
  event: "exchange" | "refusal";
  space: string;
  airlock: string;
  trace_id: string | null;
  user: string | null;
  outcome: Outcome;
  reason: string | null;
  question: string | null;
  model: string | null;
  model_calls: number | null;
  tool_calls: number | null;
  input_tokens: number | null;
  output_tokens: number | null;
  duration_ms: number | null;
};

export type TracePage = { traces: TraceSummary[]; next_cursor: string | null };

/** A span as the backend maps it; times in microseconds from the trace's first span. */
export type Step = {
  id: string;
  name: string;
  kind: string;
  service: string | null;
  start_us: number;
  duration_us: number;
  error: boolean;
  error_type: string | null;
  attributes: Record<string, unknown>;
  children: Step[];
};

export type SpansStatus = "held" | "gone" | "unavailable";

export type TraceDetail = {
  trace_id: string;
  event_id: number;
  event: AuditLine;
  spans_status: SpansStatus;
  steps: Step[] | null;
};

export type TraceFilters = { space?: string; airlock?: string; period?: Period; q?: string };

const FILTERS = ["space", "airlock", "period", "q"] as const;

/** The filters an address names; the period is the last 24 hours unless it names another. */
export function traceFiltersOf(search: string): TraceFilters {
  const read: Record<string, string> = Object.fromEntries(
    [...new URLSearchParams(search)].filter(([k]) => (FILTERS as readonly string[]).includes(k)),
  );
  return { ...read, period: periodOf(read.period ?? "", "24h") };
}

/** The page's own address for the filters: what a reload or the back button brings back. */
export function filtersSearch(filters: TraceFilters): string {
  const q = new URLSearchParams();
  for (const k of FILTERS) {
    const v = filters[k]?.trim();
    if (v && !(k === "period" && v === "24h")) q.set(k, v);
  }
  return q.size ? `?${q}` : "";
}

/** The list's query: the period as its first instant, counted back from `now`. */
export function tracesQuery(filters: TraceFilters, now: number, cursor?: string | null): string {
  const q = new URLSearchParams();
  for (const k of ["space", "airlock", "q"] as const) {
    const v = filters[k]?.trim();
    if (v) q.set(k, v);
  }
  const span = PERIODS[filters.period ?? "24h"].ms;
  if (span !== null) q.set("from", new Date(now - span).toISOString());
  if (cursor) q.set("cursor", cursor);
  return q.size ? `?${q}` : "";
}

const plural = (n: number, one: string) => `${n} ${one}${n === 1 ? "" : "s"}`;

/** "4 model calls · 3 tools"; a refusal never reached the model. */
export function stepsLabel(t: Pick<TraceSummary, "event" | "model_calls" | "tool_calls">): string {
  if (t.event === "refusal") return "admission only";
  return `${plural(t.model_calls ?? 0, "model call")} · ${plural(t.tool_calls ?? 0, "tool")}`;
}

/** Every token the exchange's model calls used, in and out; none for a refusal. */
export const totalTokens = (t: Pick<TraceSummary, "input_tokens" | "output_tokens">) =>
  (t.input_tokens ?? 0) + (t.output_tokens ?? 0);

/** "8.57 s", "9.5 ms", "3 ms", "40 µs". */
export function duration(us: number): string {
  if (us >= 1_000_000) return `${(us / 1_000_000).toFixed(2)} s`;
  if (us >= 1000) return `${(us / 1000).toFixed(us % 1000 === 0 ? 0 : 1)} ms`;
  return `${Math.round(us)} µs`;
}

const DAY = new Intl.DateTimeFormat("en-GB", {
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});
const CLOCK = new Intl.DateTimeFormat("en-GB", {
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});
const SHORT = new Intl.DateTimeFormat("en-GB", {
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
});

/** "19 Oct 2026, 10:43:33", in the reader's time zone. */
export const when = (iso: string) => DAY.format(new Date(iso));

/** A list row's time: the clock today, the day and minute before. */
export function rowTime(iso: string, now: Date): string {
  const at = new Date(iso);
  return at.toDateString() === now.toDateString() ? CLOCK.format(at) : SHORT.format(at);
}

/** The step's look: its icon and hue. */
export type Look = "model" | "tool" | "data" | "step";

/** A step as the tree and the timeline draw it: its depth, its label and its look. */
export type Row = { step: Step; depth: number; label: string; look: Look };

/**
 * The steps worth reading, depth first by start: the transport between the pods (HTTP, the tool
 * server's endpoint) and a framework's wrapper around other steps are left out, their steps
 * moved up in their place. Model calls are numbered in order.
 */
export function rowsOf(steps: Step[]): Row[] {
  const rows: Row[] = [];
  let calls = 0;
  const walk = (list: Step[], depth: number) => {
    for (const step of list) {
      const wrapper = step.kind === "other" && step.children.length > 0;
      if (step.kind === "http" || step.kind === "mcp" || wrapper) {
        walk(step.children, depth);
        continue;
      }
      if (step.kind === "model") calls += 1;
      rows.push({ step, depth, label: labelOf(step, calls), look: lookOf(step) });
      walk(step.children, depth + 1);
    }
  };
  walk(steps, 0);
  return rows;
}

const text = (v: unknown) => (v === undefined || v === null ? null : String(v));

/** The LLM a model call or an embedding names: its id, else the model the provider ran. */
const llmOf = (step: Step) =>
  text(step.attributes["aisolate.llm"]) ?? text(step.attributes["gen_ai.request.model"]);

const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

function labelOf(step: Step, call: number): string {
  const after = (prefix: string) => step.name.slice(prefix.length);
  switch (step.kind) {
    case "exchange":
      return capital(step.name);
    case "embedding":
      return llmOf(step) ? `Embedding · ${llmOf(step)}` : "Embedding";
    case "model":
      return `Model call ${call} · ${llmOf(step) ?? after("chat ")}`;
    case "tool":
      return `Tool ${text(step.attributes["aisolate.tool"]) ?? after("tool ")}`;
    case "query":
      return "Governed query";
    case "sql":
      return `SQL on ${text(step.attributes["aisolate.data_source"]) ?? after("sql ")}`;
    default:
      return capital(step.name);
  }
}

function lookOf(step: Step): Look {
  if (step.kind === "model" || step.kind === "embedding") return "model";
  if (step.kind === "tool" || step.kind === "query") return "tool";
  if (step.kind === "sql" || (step.kind === "handler" && step.attributes["aisolate.data_source"]))
    return "data";
  return "step";
}

/** Where the rows lie on the time axis: from the first one's start to the last one's end. */
export function extentOf(rows: Row[]): { start: number; total: number } {
  if (rows.length === 0) return { start: 0, total: 1 };
  const start = Math.min(...rows.map((r) => r.step.start_us));
  const end = Math.max(...rows.map((r) => r.step.start_us + r.step.duration_us));
  return { start, total: Math.max(end - start, 1) };
}

const REASONS: Record<string, string> = {
  user: "the user is not listed",
  client: "the client is not admitted",
};

const QUERY_OUTCOMES: Record<string, string> = {
  rows: "rows returned",
  empty: "no rows",
  denied: "no rule granted any row",
  invalid: "arguments the tool refused",
  error: "the query failed",
};

function handlerKind(step: Step): string {
  if (step.kind === "verifier") return "Answer verifier";
  if (/Memory$/.test(text(step.attributes["aisolate.handler"]) ?? "")) return "Conversation memory";
  return step.attributes["aisolate.stage"] === "post_llm"
    ? "Post-model handler"
    : "Context preparator";
}

/** The exchange's inputs and outputs as JSON, from its audit record: the trace holds neither. */
export const ioOf = (event: AuditLine) => ({
  inputs: JSON.stringify({ question: event.question }, null, 2),
  outputs: JSON.stringify(
    {
      answer: event.answer,
      outcome: event.outcome,
      ...(event.reason && { reason: event.reason }),
      // Only when the answer carried figures: four zeros say nothing.
      ...(event.figures &&
        Object.values(event.figures).some(Boolean) && { figures: event.figures }),
    },
    null,
    2,
  ),
});

/** One fact of a step's details: a label and a value. */
export type Fact = [string, string];

/** `io`, on the exchange alone: its inputs and outputs as JSON, from the audit record, as MLflow shows a root span's. */
export type Details = {
  kind: string;
  facts: Fact[];
  args: string | null;
  io: { inputs: string; outputs: string } | null;
};

/**
 * What the details pane says of a row: its attributes in plain words, and for a tool call the
 * arguments and the data source the audit record keeps (a span holds neither).
 */
export function detailsOf(rows: Row[], index: number, event: AuditLine): Details {
  const { step } = rows[index];
  const a = step.attributes;
  const facts: Fact[] = [];
  const add = (label: string, value: unknown) => {
    const v = text(value);
    if (v !== null && v !== "") facts.push([label, v]);
  };
  let kind = "Step";
  let args: string | null = null;
  let io: Details["io"] = null;
  switch (step.kind) {
    case "exchange": {
      kind = "Exchange";
      add(
        "Airlock",
        `${event.space}/${event.airlock}${event.revision ? ` · revision ${event.revision}` : ""}`,
      );
      add("Client", a["aisolate.client"]);
      add("Outcome", outcomeLabel(event));
      io = ioOf(event);
      break;
    }
    case "admission":
      kind = "Admission";
      add(
        "Admitted",
        a["aisolate.admitted"] === false || a["aisolate.admitted"] === "false"
          ? `no: ${REASONS[text(a["aisolate.reason"]) ?? ""] ?? text(a["aisolate.reason"]) ?? "refused"}`
          : "yes",
      );
      break;
    case "handler":
    case "verifier":
      kind = handlerKind(step);
      add("Handler", a["aisolate.handler"]);
      add("Data source", a["aisolate.data_source"]);
      if (a["aisolate.passages"] !== undefined && a["aisolate.stage"] === "pre_llm") {
        const n = Number(a["aisolate.passages"]);
        add("Passages", `${plural(n, "passage")} put in the prompt`);
      }
      add("Verdict", a["aisolate.verdict"]);
      break;
    case "embedding":
      kind = "Embedding";
      add("Model", llm(step));
      add("Tokens in", a["gen_ai.usage.input_tokens"]);
      break;
    case "model": {
      kind = "Model call";
      add("Model", llm(step));
      const tin = a["gen_ai.usage.input_tokens"];
      const tout = a["gen_ai.usage.output_tokens"];
      if (tin !== undefined || tout !== undefined)
        add("Tokens in · out", `${number(Number(tin ?? 0))} · ${number(Number(tout ?? 0))}`);
      add("Result", resultOf(rows, index));
      break;
    }
    case "tool": {
      kind = "Tool call";
      const name = text(a["aisolate.tool"]) ?? step.name.replace(/^tool /, "");
      const recorded = auditToolOf(rows, index, name, event);
      add("Tool", name);
      add("Data source", recorded?.data_source);
      add("Entity", entityUnder(rows, index));
      add("Rows returned", a["aisolate.rows"] ?? recorded?.rows);
      if (recorded?.args) args = JSON.stringify(recorded.args, null, 2);
      break;
    }
    case "query":
      kind = "Governed query";
      add("Data source", a["aisolate.data_source"]);
      add("Entity", a["aisolate.entity"]);
      add("Rows", a["aisolate.rows"]);
      add("Outcome", QUERY_OUTCOMES[text(a["aisolate.outcome"]) ?? ""] ?? a["aisolate.outcome"]);
      break;
    case "sql":
      kind = "SQL";
      add("Data source", a["aisolate.data_source"]);
      add("Entity", a["aisolate.entity"]);
      add("Statement", "not in the trace; the audit record keeps its hash");
      break;
    default:
      for (const [k, v] of Object.entries(a)) add(k, v);
  }
  if (step.error)
    add("Error", step.error_type ? `${step.error_type}: this step failed` : "this step failed");
  return { kind, facts, args, io };
}

function llm(step: Step): string | null {
  const id = text(step.attributes["aisolate.llm"]);
  const model = text(step.attributes["gen_ai.request.model"]);
  return id && model ? `${id} · ${model}` : (id ?? model);
}

/** What a model call led to: the tools it asked for before the next call, else the answer. */
function resultOf(rows: Row[], index: number): string {
  const { depth } = rows[index];
  const asked: string[] = [];
  for (const r of rows.slice(index + 1)) {
    if (r.depth < depth || (r.depth === depth && r.step.kind === "model")) break;
    if (r.depth === depth && r.step.kind === "tool") asked.push(r.label.replace(/^Tool /, ""));
  }
  return asked.length ? `asks for ${asked.join(", ")}` : "the answer";
}

/** The audit's record of this call: the n-th call of a tool is its n-th entry of that name. */
function auditToolOf(rows: Row[], index: number, name: string, event: AuditLine) {
  const nth = rows
    .slice(0, index)
    .filter((r) => r.step.kind === "tool" && r.label === `Tool ${name}`).length;
  return event.tools.filter((t) => t.name === name)[nth] ?? null;
}

/** The entity the call's governed query read, from the step under it. */
function entityUnder(rows: Row[], index: number): string | null {
  const { depth } = rows[index];
  for (const r of rows.slice(index + 1)) {
    if (r.depth <= depth) break;
    const entity = text(r.step.attributes["aisolate.entity"]);
    if (entity) return entity;
  }
  return null;
}

/** Why the page shows no steps, in a sentence; null while it can. */
export function spansMessage(status: SpansStatus): string | null {
  if (status === "gone") {
    return "The trace store no longer holds this trace's steps: it keeps traces for a while only. The audit record is kept.";
  }
  if (status === "unavailable") return "The trace store did not answer. Try again in a moment.";
  return null;
}
