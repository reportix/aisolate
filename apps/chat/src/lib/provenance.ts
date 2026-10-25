import type { Root, RootContent } from "hast";
import type {
  Call,
  Cell,
  Computed,
  Evidence,
  Figure,
  FigureSource,
  FigureStatus,
  Grounding,
  Message,
  Operand,
  Quote,
  Table,
  ToolError,
} from "./types";

const STATUSES: FigureStatus[] = ["sourced", "computed", "unsourced", "mismatch"];

type Loose<T> = Partial<Record<keyof T, unknown>> | null | undefined;

const isCell = (c: unknown): boolean => {
  const x = c as Loose<Figure>;
  return typeof x?.display === "string" && STATUSES.includes(x.status as FigureStatus);
};
const isFigure = (f: unknown): f is Figure =>
  typeof (f as Loose<Figure>)?.id === "string" && isCell(f);
const isTable = (t: unknown): t is Table => {
  const x = t as Loose<Table>;
  return (
    typeof x?.id === "string" &&
    Array.isArray(x.columns) &&
    Array.isArray(x.rows) &&
    x.rows.every((row) => Array.isArray(row) && row.every(isCell))
  );
};
const isQuote = (q: unknown): q is Quote => {
  const x = q as Loose<Quote>;
  return typeof x?.id === "string" && typeof x.text === "string";
};

/**
 * The grounding a final event carries, or nothing when it has none or any part of it does not hold
 * together: the answer then renders from its streamed text, as before.
 */
export function groundingOf(e: Partial<Record<keyof Grounding, unknown>>): Partial<Grounding> {
  const { segments, figures, tables = [], quotes = [], provenance } = e;
  if (
    !Array.isArray(segments) ||
    !Array.isArray(figures) ||
    !figures.every(isFigure) ||
    !Array.isArray(tables) ||
    !tables.every(isTable) ||
    !Array.isArray(quotes) ||
    !quotes.every(isQuote)
  )
    return {};
  const ids = (xs: { id: string }[]) => new Set(xs.map((x) => x.id));
  const [f, t, q] = [ids(figures), ids(tables), ids(quotes)];
  const whole = segments.every(
    (s) => typeof s?.text === "string" || f.has(s?.figure) || t.has(s?.table) || q.has(s?.quote),
  );
  if (!whole) return {};
  const p = provenance as Grounding["provenance"];
  return {
    segments,
    figures,
    tables,
    quotes,
    ...(typeof p?.status === "string" && { provenance: p }),
  };
}

// Private-use characters mark where a figure, a quote or a table goes; the model's own text can
// never carry one.
const MARKS = /[\uE000\uE001]/g;
const MARK = /\uE000([fqt]\d+)\uE001/;

/** The answer's markdown, each figure, quote and table a mark that `rehypeMarks` turns into its own element. */
export function answerMarkdown({ segments, figures, tables = [], quotes = [] }: Grounding): string {
  const at = (kind: string, xs: { id: string }[], id: string) =>
    `\uE000${kind}${xs.findIndex((x) => x.id === id)}\uE001`;
  // The airlock writes a quote into the text, its words in quotation marks: they become its mark.
  const placed = new Set(segments.flatMap((s) => ("quote" in s ? [s.quote] : [])));
  const quoted = (text: string) =>
    quotes.reduce(
      (t, q, i) => (placed.has(q.id) ? t : t.replace(`"${q.text}"`, () => `\uE000q${i}\uE001`)),
      text,
    );
  return segments
    .map((s) => {
      if ("text" in s) return quoted(s.text.replace(MARKS, ""));
      if ("figure" in s) return at("f", figures, s.figure);
      if ("quote" in s) return at("q", quotes, s.quote);
      // A table is a block: a paragraph of its own, whatever the text around it.
      return `\n\n${at("t", tables, s.table)}\n\n`;
    })
    .join("");
}

/**
 * Turns each mark into `<span data-mark="f0">` wherever the markdown put it (a paragraph, a cell),
 * and a table's into `<div data-mark="t0">` in place of the paragraph that held it.
 */
export function rehypeMarks() {
  return (tree: Root) => mark(tree);
}

function mark(node: { children: RootContent[] }) {
  node.children = node.children.flatMap((child): RootContent[] => {
    if (child.type === "element") {
      mark(child);
      const [only] = child.children;
      if (
        child.tagName === "p" &&
        child.children.length === 1 &&
        only.type === "element" &&
        only.tagName === "div"
      )
        return [only];
    }
    if (child.type !== "text") return [child];
    return child.value
      .split(MARK)
      .map((part, i): RootContent =>
        i % 2
          ? {
              type: "element",
              tagName: part.startsWith("t") ? "div" : "span",
              properties: { dataMark: part },
              children: [],
            }
          : { type: "text", value: part },
      )
      .filter((n) => n.type !== "text" || n.value !== "");
  });
}

const MARKS_ALL = new RegExp(MARK, "g");

/** The marks of an answer's markdown in reading order: "f0", "t0", "q1". */
export const marksIn = (markdown: string): string[] =>
  [...markdown.matchAll(MARKS_ALL)].map((m) => m[1]);

/** The evidence a figure stands on; none for one the model typed. */
export function evidenceOfFigure({ status, source, computed: c }: Omit<Figure, "id">): string[] {
  if (status === "unsourced") return [];
  const ids = [source?.evidence, c?.by?.evidence, ...(c?.of ?? []).map((o) => o.evidence)];
  return [...new Set(ids.filter((id): id is string => !!id))];
}

/** Each evidence the answer cites, once, in the order the reader meets it. */
export function citedEvidence(
  markdown: string,
  { figures, tables = [], quotes = [] }: Grounding,
): string[] {
  const cited = marksIn(markdown).flatMap((mark) => {
    const i = Number(mark.slice(1));
    if (mark[0] === "f") return evidenceOfFigure(figures[i]);
    const ev = (mark[0] === "q" ? quotes[i] : tables[i])?.evidence;
    return ev ? [ev] : [];
  });
  return [...new Set(cited)];
}

/** A name as words: "hr.compensation" is "compensation", "gross_salary" "gross salary". */
export const words = (name: string) => name.slice(name.lastIndexOf(".") + 1).replaceAll("_", " ");

const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const lower = (s: string) => s.charAt(0).toLowerCase() + s.slice(1);

/**
 * A column as words: a currency it names in parentheses, "amount_eur" is "amount (EUR)", and
 * dropped where the figure beside it already shows that currency.
 */
const columnWords = (column: string, unit?: string) =>
  words(
    unit && /^[A-Z]{3}$/.test(unit) ? column.replace(new RegExp(`_${unit}$`, "i"), "") : column,
  ).replace(/ (eur|usd|gbp|chf)$/i, (_, code: string) => ` (${code.toUpperCase()})`);

const OPS: Record<string, string> = {
  eq: "is",
  ne: "is not",
  neq: "is not",
  gt: "is above",
  gte: "is at least",
  lt: "is below",
  lte: "is at most",
  in: "is one of",
  contains: "contains",
  ilike: "contains",
};

const FUNCTIONS: Record<string, string> = {
  avg: "average",
  average: "average",
  sum: "total",
  min: "lowest",
  max: "highest",
};

/** "status is overdue"; nothing for an operator it does not know, rather than its JSON. */
function filterWords(f: unknown): string | null {
  const { column, op, value } = (f ?? {}) as Record<string, unknown>;
  const said = typeof op === "string" ? OPS[op.toLowerCase()] : undefined;
  if (!said || typeof column !== "string" || value === undefined || value === null) return null;
  return `${columnWords(column)} ${said} ${Array.isArray(value) ? value.join(", ") : String(value)}`;
}

const isAggregate = (call?: Call) => typeof call?.args?.function === "string";

/** What a tool call read, in words: "Invoices where status is overdue", "Average gross salary per department". */
export function callWords({ tool, args = {} }: Call, entity?: string): string {
  const named = entity ?? (typeof args.entity === "string" ? args.entity : tool);
  const filters = (Array.isArray(args.filters) ? args.filters : []).map(filterWords);
  const where = filters.some(Boolean) ? ` where ${filters.filter(Boolean).join(" and ")}` : "";
  if (!isAggregate({ tool, args })) return capital(words(named)) + where;
  const fn = String(args.function).toLowerCase();
  const what =
    fn === "count" || typeof args.column !== "string"
      ? `Number of ${words(named)}`
      : `${capital(FUNCTIONS[fn] ?? fn)} ${columnWords(args.column)}`;
  const per = typeof args.group_by === "string" ? ` per ${words(args.group_by)}` : "";
  return what + per + where;
}

export const count = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** One row of an answer's Sources: what was read, in words, and the call behind it for the curious. */
export type SourceRow = {
  key: string;
  label: string;
  detail: string;
  call?: Call;
  /** A document search's source, where the call is none. */
  document?: string;
  rows?: number;
  /** Read, but held nothing the asking user may see: shown with a dash, not a number. */
  none?: boolean;
  /** Still running: shown with a pulsing dot. */
  running?: boolean;
  /** Why the call failed, in words: the row is marked Failed. */
  error?: string;
  /** What the call returned, for the curious: its rows or passages, rules and query hash, as the audit trail has them. */
  evidence?: Evidence;
  /** The cells of that evidence the answer's figures were taken from, each as "row key\u0000column". */
  citedCells?: Set<string>;
  /** The passages (1-based) of that evidence the answer quotes. */
  citedPassages?: Set<number>;
};

/** The cells of one evidence the answer's figures and tables were taken from, as `citedCells` keys them. */
export function citedCells(
  id: string,
  { figures = [], tables = [] }: Partial<Grounding>,
): Set<string> {
  const sources: (FigureSource | undefined)[] = [];
  for (const f of figures)
    sources.push(f.source, ...(f.computed?.of ?? []), ...(f.computed?.rows ?? []), f.computed?.row);
  for (const t of tables) for (const row of t.rows) for (const c of row) sources.push(c.source);
  return new Set(
    sources.flatMap((s) =>
      s?.evidence === id && s.row_key != null && s.column ? [`${s.row_key}\u0000${s.column}`] : [],
    ),
  );
}

/**
 * Why a call failed, in words, about whatever it talked to (a tool need not read data); a category
 * this build does not know reads as `failed`.
 */
const TOOL_ERRORS: Record<ToolError, string> = {
  login_refused: "the login was refused",
  unreachable: "could not be reached",
  failed: "did not complete",
};

/**
 * The answer's sources, numbered: the evidence it cites in reading order, then what else the
 * exchange read, then the calls still running or that never returned.
 */
/** The suffix the airlock gives a passage's title when it is the asking user's own document. */
const OWN = " (the asking user's own)";
/** A filter column that names a person: its value owns the rows ("Pat Smith's contract terms"). */
const PERSON = /(^|_)(employee|name|person|user|owner|client|customer)$/;

/** "Employment Contract Pat Smith (the asking user's own)" → "Your employment contract". */
export function ownTitle(title: string, userName?: string): string {
  if (!title.endsWith(OWN)) return title;
  const bare = title.slice(0, -OWN.length).trim();
  // The owner's name gone, what is left is a common title ("Employment Contract"); with the name
  // still in it, only the first word is safe to lowercase.
  const rest = (userName ? bare.replace(userName, "") : bare).replace(/\s+/g, " ").trim();
  return rest ? `Your ${userName ? rest.toLowerCase() : lower(rest)}` : "Your document";
}

/** One filter on a person's name: "Pat Smith's contract terms"; nothing for any other call. */
function personLabel({ args = {} }: Call, entity?: string): string | null {
  const filters = Array.isArray(args.filters) ? args.filters : [];
  if (filters.length !== 1 || !entity || isAggregate({ tool: "", args })) return null;
  const { column, op, value } = (filters[0] ?? {}) as Record<string, unknown>;
  const named = typeof op === "string" && ["eq", "ilike"].includes(op.toLowerCase());
  if (!named || typeof column !== "string" || !PERSON.test(column) || typeof value !== "string")
    return null;
  return `${value}'s ${words(entity)}`;
}

export function sourceRows(m: Message, cited: string[], userName?: string): SourceRow[] {
  const { tables = [], quotes = [], figures = [] } = m;
  const order = [...new Set([...cited, ...m.evidence.map((e) => e.id)])];
  const evidenceRows = order.map((id): SourceRow => {
    const ev = m.evidence.find((e) => e.id === id);
    const tool = m.toolCalls.find((c) => c.id === ev?.call_id);
    const call =
      tool ??
      tables.find((t) => t.evidence === id)?.call ??
      figures.find((f) => f.computed?.by?.evidence === id)?.computed?.call;
    const quote = quotes.find((q) => q.evidence === id && q.title);
    const cited = {
      citedCells: citedCells(id, m),
      citedPassages: new Set(
        quotes.flatMap((q) =>
          q.evidence === id && q.status === "quoted" && q.index ? [q.index] : [],
        ),
      ),
    };
    const doc = { document: ev?.tool, rows: ev?.row_count, evidence: ev, ...cited };
    if (quote) {
      const label = [ownTitle(quote.title!, userName), quote.section].filter(Boolean).join(" · ");
      return { key: id, label, detail: "document", ...doc };
    }
    if (!call && ev)
      return {
        key: id,
        label: capital(words(ev.tool)),
        detail: `${count(ev.row_count, "section")} read`,
        ...doc,
      };
    const entity =
      ev?.entity ??
      tables.find((t) => t.evidence === id)?.entity ??
      figures.find((f) => f.source?.evidence === id)?.source?.entity;
    // The asking user's own row: a figure of it names them, or the tool marked the one row self.
    const own =
      ev &&
      call &&
      !(Array.isArray(call.args?.filters) && call.args.filters.length) &&
      (figures.some(
        (f) => f.source?.evidence === id && !!userName && f.source.subject === userName,
      ) ||
        (ev.row_count === 1 && ev.columns.includes("self")));
    const none = ev?.row_count === 0;
    const label = own
      ? `Your ${words(entity ?? "data")}`
      : ((call && personLabel(call, entity)) ??
        (call ? callWords(call, entity) : capital(words(entity ?? "data"))));
    return {
      key: id,
      label,
      detail: none ? "nothing you may read" : own ? "" : ev ? count(ev.row_count, "row") : "",
      call: call && { tool: call.tool, args: call.args },
      rows: ev?.row_count,
      evidence: ev,
      ...cited,
      none,
    };
  });
  const answered = new Set(m.evidence.map((e) => e.call_id));
  const pending = m.toolCalls
    .filter((c) => !answered.has(c.id))
    .map((c): SourceRow => {
      const running = !c.error && m.status === "streaming";
      return {
        key: c.id,
        label: callWords(c),
        detail: c.error || running ? "" : "did not finish",
        call: { tool: c.tool, args: c.args },
        running,
        error: c.error ? (TOOL_ERRORS[c.error] ?? TOOL_ERRORS.failed) : undefined,
      };
    });
  return [...evidenceRows, ...pending];
}

/** What the card of a figure needs from the answer around it: the calls and the sources' numbers. */
export type Context = {
  call: (evidence: string) => Call | undefined;
  number: (evidence: string) => number | undefined;
};

/** The context of an answer: its calls by evidence, its sources numbered from 1. */
export function contextOf(rows: SourceRow[]): Context {
  const numbers = new Map(rows.map((r, i) => [r.key, i + 1]));
  return {
    call: (id) => rows.find((r) => r.key === id)?.call,
    number: (id) => numbers.get(id),
  };
}

/** A sourced cell in words: "From compensation · Elena Varga · gross salary". */
function cellWords(s: FigureSource, ctx: Context): string {
  const call = ctx.call(s.evidence);
  // A group's row: named by its group, its column what the call aggregated.
  const group = isAggregate(call);
  const column =
    group && s.column === "value"
      ? lower(callWords({ tool: "", args: { ...call!.args, group_by: undefined, filters: [] } }))
      : group && s.column === "n"
        ? "count"
        : s.column && columnWords(s.column, s.unit);
  const parts = [
    s.entity && words(s.entity),
    s.subject ?? (group ? s.row_key : undefined),
    column,
    s.period,
  ];
  return `From ${parts.filter(Boolean).join(" · ")}`;
}

const INFIX: Record<string, string> = {
  add: "+",
  sum: "+",
  subtract: "−",
  multiply: "×",
  divide: "÷",
};
const NAMED: Record<string, string> = {
  add: "Sum",
  sum: "Sum",
  subtract: "Difference",
  multiply: "Product",
  divide: "Ratio",
  average: "Average",
  min: "Lowest",
  max: "Highest",
  count: "Count",
};

/** A computation in words with its inputs: "Sum of 3 invoices: 45,000 + 27,500 + 17,500 EUR". */
export function computedWords(c: Computed): string {
  const of = c.of ?? [];
  const entity = words(c.by?.entity ?? of[0]?.entity ?? "");
  // The entity names the rows when it is a plural ("invoices"); else they are values.
  // ponytail: an English plural by its "s"; a template in another language reads "values".
  const things = entity.endsWith("s") ? entity : "values";
  if (c.op === "top_n" || c.op === "bottom_n") {
    const by = c.by?.column ? ` by ${columnWords(c.by.column)}` : "";
    return `${c.op === "top_n" ? "Top" : "Bottom"} ${c.n ?? c.rows?.length ?? ""} ${things}${by}`;
  }
  if (c.by && (c.op === "max" || c.op === "min")) {
    const of_ = c.by.column ? ` ${columnWords(c.by.column)}` : "";
    const row = c.row?.subject ?? c.row?.row_key;
    return `${c.op === "max" ? "Highest" : "Lowest"}${of_} of ${things}${row ? `: ${row}` : ""}`;
  }
  const unit = of[0]?.unit && of.every((o) => o.unit === of[0].unit) ? of[0].unit : undefined;
  const bare = (o: Operand) => {
    const shown = o.display ?? o.value ?? "?";
    return unit ? shown.replace(new RegExp(`\\s?${unit.replace(/[^\w]/g, "\\$&")}$`), "") : shown;
  };
  // Two operands about different rows, else of different columns, are told apart by name.
  const differ = (key: (o: Operand) => string | undefined) =>
    of.length === 2 && new Set(of.map(key)).size > 1;
  const who = (o: Operand) => o.subject ?? o.row_key;
  const byColumn = !differ(who) && differ((x) => x.column);
  const named = (o: Operand) => {
    const name = differ(who) ? who(o) : byColumn && o.column && columnWords(o.column, unit);
    return name ? `${name} ${bare(o)}` : bare(o);
  };
  // Columns that name their unit ("taken days") need it no more at the end.
  const suffix = !unit || byColumn ? "" : unit === "%" ? "%" : ` ${unit}`;
  const values = of.map(named);
  const title = NAMED[c.op] ?? capital(words(c.op));
  if (c.op === "percent_change") return `Change from ${values.join(" to ")}${suffix}`;
  if (c.op === "count") return `Count of ${count(of.length, things.replace(/s$/, ""), things)}`;
  const joined = INFIX[c.op] ? values.join(` ${INFIX[c.op]} `) : values.join(", ");
  const many = c.op === "add" || c.op === "sum" || !INFIX[c.op] ? ` of ${of.length} ${things}` : "";
  return `${title}${many}: ${joined}${suffix}`;
}
/**
 * A mismatch's reason in plain words: an evidence id becomes its source's number, a row handle
 * and a placeholder go, a name reads as words.
 */
export function plainReason(reason: string, ctx: Context): string {
  const source = (id: string) => {
    const n = ctx.number(id);
    return n ? `source ${n}` : "a source this answer did not read";
  };
  return reason
    .replace(
      /there is no (?:evidence|document) ev-\d+ in this exchange/,
      "it points to data this answer never read",
    )
    .replace(
      /\br\d+ is a row of (ev-\d+), not of (ev-\d+)/,
      (_, a, b) => `the row is from ${source(a)}, not ${source(b)}`,
    )
    .replace(/\bno row r\d+/, "no such row")
    .replace(/ \(r\d+\)/g, "")
    .replace(/\bev-\d+ \(([\w.]+)\)/g, (_, entity: string) => words(entity))
    .replace(/\bev-\d+/g, source)
    .replace(/\{[A-Za-z][\w-]*\}/g, "a figure")
    .replace(/\b[a-z]+\.[a-z_]+\b/g, words)
    .replace(/\b\w+_\w+\b/g, (w) => w.replaceAll("_", " "));
}

/** What a figure's card says, in plain words, never an id. */
export function figureCard(f: Omit<Figure, "id">, ctx: Context): string {
  switch (f.status) {
    case "sourced":
      return f.source ? cellWords(f.source, ctx) : "From the data";
    case "computed":
      return f.computed ? computedWords(f.computed) : "Computed by AIsolate from the data";
    case "unsourced": {
      // The airlock's hint names the cell that holds the same value ("ev-3 r4 drug holds the same
      // value"): the reader gets the fact, never the ids.
      const held = f.reason?.includes(" holds the same value")
        ? " The records hold the same value."
        : "";
      return `The model typed this instead of citing it.${held}`;
    }
    case "mismatch":
      return `This doesn't match its source: ${f.reason ? plainReason(f.reason, ctx) : "the check failed"}`;
  }
}

/** What a quote's card says: the document and section it is from, or why its words are not there. */
export function quoteCard(q: Quote, ctx: Context): string {
  const passage = [q.title, q.section].filter(Boolean).join(" · ") || "its document";
  if (q.status === "quoted") return `Quoted from ${passage}`;
  if (!q.reason || q.reason.startsWith("these words are not in"))
    return `These words aren't in ${passage}`;
  return `This quote doesn't hold: ${plainReason(q.reason, ctx)}`;
}

/** What a table's card says: the rows it shows, or why they are not what they claim. */
export function tableCard(t: Table, ctx: Context): string {
  const read = t.call ? callWords(t.call, t.entity) : capital(words(t.entity ?? "data"));
  if (t.status !== "mismatch") return `Rows of ${lower(read)}`;
  return `This table doesn't match its source: ${t.reason ? plainReason(t.reason, ctx) : "the check failed"}`;
}

/**
 * The answer's one verdict line: every figure checked, or how many need care; nothing for an
 * answer without figures whose quotes all hold.
 */
export function careLine({ figures, tables = [], quotes = [], provenance: p }: Grounding): {
  ok: boolean;
  text: string;
} | null {
  const bad = (s: string) => s === "unsourced" || s === "mismatch";
  const figuresBad = p
    ? p.unsourced + p.mismatch
    : figures.filter((f) => bad(f.status)).length + tables.filter((t) => bad(t.status)).length;
  const quotesBad = quotes.filter((q) => q.status === "mismatch").length;
  const checked = p ? p.status !== "no_figures" : figures.length + tables.length > 0;
  if (figuresBad + quotesBad === 0)
    return checked ? { ok: true, text: "Every figure checked against its source" } : null;
  // One fixed line for every problem, whatever its cause; the chips and the Sources say which.
  const marked = [figuresBad && "figures", quotesBad && "quotes"].filter(Boolean).join(" and ");
  const mismatch =
    (p ? p.mismatch > 0 : figures.some((f) => f.status === "mismatch")) ||
    tables.some((t) => t.status === "mismatch") ||
    quotesBad > 0;
  return {
    ok: false,
    text: `The marked ${marked} ${mismatch ? "don't match their source" : "have no source"}. This answer is unverified.`,
  };
}

/** A tool call as the curious read it: "read_ledger(order: income desc, limit: 5)". */
export function callLabel({ tool, args }: Call): string {
  const shown = Object.entries(args ?? {}).map(
    ([k, v]) => `${k}: ${typeof v === "string" ? v : JSON.stringify(v)}`,
  );
  return `${tool}(${shown.join(", ")})`;
}

/** A column as a table head reads it, in sentence case: "gross_salary" is "Gross salary". */
export function heading(column: string): string {
  return capital(columnWords(column));
}

/** Whether a cell holds a number: its exact value, else what it shows, reads as one. */
const isNumber = (c?: Cell) => {
  const v = (c?.value ?? c?.display ?? "").trim();
  return v !== "" && Number.isFinite(Number(v));
};

/** For each column of a table, whether every one of its cells is a number. */
export function numericColumns({ columns, rows }: Table): boolean[] {
  return columns.map((_, i) => rows.length > 0 && rows.every((row) => isNumber(row[i])));
}
