import type { Grounding } from "@aisolate/ui";
import { PERIODS, type Period, periodOf } from "./period";
import { number } from "./text";

// The shapes of the backend's audit endpoints (docs/api.md, The audit endpoints).

export type Outcome = "answered" | "dont_know" | "refused" | "error";

export type Figures = { sourced: number; computed: number; unsourced: number; mismatch: number };

/** One line of the list: its summary. */
export type AuditSummary = {
  id: number;
  received_at: string;
  ts: string;
  event: "exchange" | "refusal";
  space: string;
  airlock: string;
  revision: string | null;
  trace_id: string | null;
  user: string | null;
  client: string | null;
  outcome: Outcome;
  reason: string | null;
  question: string | null;
  figures: Figures | null;
  input_tokens: number | null;
  output_tokens: number | null;
  duration_ms: number;
};

export type AuditPage = { events: AuditSummary[]; next_cursor: string | null };

export type AuditTool = {
  name: string;
  /** null when the call was refused: an argument it refused is never recorded. */
  args: Record<string, unknown> | null;
  data_source: string | null;
  rows: number | null;
  rule_version: string | null;
  sql_hash: string | null;
  rows_hash: string | null;
  /** Why a call read nothing; null or absent when it read its data. */
  error?: "login_refused" | "unreachable" | "failed" | null;
};

/** A data source the airlock's newest exchange that called a tool could not read. */
export type DataError = {
  data_source: string | null;
  error: "login_refused" | "unreachable";
  ts: string;
};

const DATA_ERRORS: Record<DataError["error"], [said: string, fix: string]> = {
  login_refused: ["the database refused the login", "its password"],
  unreachable: ["the database could not be reached", "its URL"],
};

/** "Data source hr: the database refused the login, at 10:43. Check its password on the Data sources page." */
export function dataErrorMessage({ data_source, error }: DataError, at: string): string {
  const [said, fix] = DATA_ERRORS[error] ?? DATA_ERRORS.unreachable;
  const name = data_source ? `Data source ${data_source}` : "A data source";
  return `${name}: ${said}, at ${at}. Check ${fix} on the Data sources page.`;
}

/** The audit line as the airlock pod sent it. */
export type AuditLine = Omit<
  AuditSummary,
  "id" | "received_at" | "input_tokens" | "output_tokens"
> & {
  answer: string | null;
  tools: AuditTool[];
  evidence: string[];
  verifiers: { name: string; verdict: "PASS" | "WARN" | "FAIL" }[];
  grounding: Grounding | null;
  model: {
    provider: string;
    name: string;
    calls: number;
    input_tokens: number | null;
    output_tokens: number | null;
  } | null;
};

export type AuditDetail = {
  id: number;
  received_at: string;
  prev_hash: string;
  hash: string;
  event: AuditLine;
};

export type ChainStatus = {
  space: string;
  airlock: string;
  status: "ok" | "broken";
  events: number;
  first_id: number | null;
  last_id: number | null;
  head: string | null;
  broken: { id: number; reason: "altered" | "removed" } | null;
};

/**
 * The Audit page's one search, over the space, airlock, user, client, trace id and
 * question, and Monitoring's periods; all time unless one is picked.
 */
export type AuditFilters = { q?: string; period?: Period };

/** The export's files: a PDF to read, the lines as JSON, the AIsolate Audit XBRL report. */
export type ExportFormat = "pdf" | "json" | "xbrl";

/** The filters a page address names; a trace viewer's `?trace_id=` link becomes the search. */
export function auditFiltersOf(search: string): AuditFilters {
  const read = new URLSearchParams(search);
  const q = read.get("q") ?? read.get("trace_id");
  return { ...(q && { q }), period: periodOf(read.get("period") ?? "", "all") };
}

/** The page's own address for the filters, so a reload or the back button keeps them. */
export function auditSearch({ q, period }: AuditFilters): string {
  const s = new URLSearchParams();
  if (q?.trim()) s.set("q", q.trim());
  if (period && period !== "all") s.set("period", period);
  return s.size ? `?${s}` : "";
}

const OUTCOMES: Record<Outcome, string> = {
  answered: "Answered",
  dont_know: "Don't know",
  refused: "Refused",
  error: "Error",
};

/** A refusal's reason in words; `user` is what lines written before roles carry. */
export const REASONS: Record<string, string> = {
  role: "no listed role",
  user: "user not listed",
  client: "client not admitted",
  llm_unavailable: "model unavailable",
  no_model_endpoint: "no model endpoint",
  deadline: "out of time",
  internal: "internal",
};

/** "Answered": an outcome by its word, the backend's own for one the UI does not know. */
export const outcomeOf = (o: Outcome) => OUTCOMES[o] ?? o;

/** "Answered", or "Refused · no listed role" with the reason a refusal or an error names. */
export const outcomeLabel = ({ outcome, reason }: Pick<AuditSummary, "outcome" | "reason">) =>
  reason ? `${outcomeOf(outcome)} · ${REASONS[reason] ?? reason}` : outcomeOf(outcome);

/** The list's query: the search, the period as its first instant counted back from `now`, the cursor. */
export function eventsQuery(filters: AuditFilters, now: number, cursor?: string | null): string {
  const q = new URLSearchParams();
  if (filters.q?.trim()) q.set("q", filters.q.trim());
  const span = PERIODS[filters.period ?? "all"].ms;
  if (span !== null) q.set("from", new Date(now - span).toISOString());
  if (cursor) q.set("cursor", cursor);
  return q.size ? `?${q}` : "";
}

/** The trace's address from `NEXT_PUBLIC_TRACE_URL`'s template, or nothing without one or a trace. */
export const traceLink = (template: string | undefined, traceId: string | null) =>
  template && traceId ? template.replaceAll("{trace_id}", encodeURIComponent(traceId)) : null;

/** When it began, in UTC to the second, as the filters read dates: "2026-10-18 09:11:57". */
export const utc = (iso: string) => iso.slice(0, 19).replace("T", " ");

/** "1,840 / 212", or a dash when the provider reported none. */
export const tokens = (input: number | null, output: number | null) =>
  input === null && output === null ? "—" : `${number(input ?? 0)} / ${number(output ?? 0)}`;

/** What verify says of the airlock's chain, in one line. */
export function chainLine(c: ChainStatus): string {
  if (c.broken) {
    return `Broken at event ${c.broken.id}: ${c.broken.reason === "altered" ? "its content no longer gives its hash" : "an event before it is gone"}`;
  }
  return c.events === 0
    ? "Intact: no events yet"
    : `Intact: ${number(c.events)} ${c.events === 1 ? "event" : "events"}, ${c.first_id} to ${c.last_id}`;
}
