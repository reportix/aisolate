// An answer's grounding as the audit keeps it (GET /v1/audit/events/{id}): the chat's
// shapes less what the cells held, so no value, subject, period or unit. The marks read as the chat's.

/** Where a figure came from: copied from a cell, computed from cells, typed by the model, or contradicted. */
export type FigureStatus = "sourced" | "computed" | "unsourced" | "mismatch";

export type FigureSource = { evidence: string; entity?: string; column?: string; row_key?: string };

/** A cell a computation read; `display` only where the line kept it. */
export type Operand = FigureSource & { display?: string };

export type Figure = {
  id: string;
  /** As the reader sees it, formatted by the airlock: "78,000 EUR". */
  display: string;
  status: FigureStatus;
  source?: FigureSource;
  computed?: { op: string; of?: Operand[] };
  reason?: string;
};

export type Cell = Pick<Figure, "display" | "status" | "source">;

export type Call = { tool: string; args?: Record<string, unknown> | null };

export type GroundedTable = {
  id: string;
  status: "sourced" | "mismatch";
  evidence?: string;
  entity?: string;
  call?: Call;
  columns: string[];
  rows: Cell[][];
  reason?: string;
};

export type Quote = {
  id: string;
  status: "quoted" | "mismatch";
  evidence: string;
  index?: number;
  title?: string;
  section?: string;
  text: string;
  reason?: string;
};

export type Segment = { text: string } | { figure: string } | { table: string } | { quote: string };

export type Grounding = {
  segments: Segment[];
  figures: Figure[];
  tables?: GroundedTable[];
  quotes?: Quote[];
};

/** One piece of an answer in reading order, each mark resolved to what it stands for. */
export type Piece =
  { text: string } | { figure: Figure } | { table: GroundedTable } | { quote: Quote };

/**
 * The answer in pieces. The airlock writes a quote into the text, its words in quotation marks: those
 * words become the quote's mark, as the chat shows them. A mark that names nothing is left out.
 */
export function piecesOf({ segments, figures, tables = [], quotes = [] }: Grounding): Piece[] {
  const placed = new Set(segments.flatMap((s) => ("quote" in s ? [s.quote] : [])));
  const inText = quotes.filter((q) => !placed.has(q.id));
  const split = (text: string): Piece[] => {
    for (const q of inText) {
      const at = text.indexOf(`"${q.text}"`);
      if (at < 0) continue;
      return [
        ...split(text.slice(0, at)),
        { quote: q },
        ...split(text.slice(at + q.text.length + 2)),
      ];
    }
    return text ? [{ text }] : [];
  };
  return segments.flatMap((s): Piece[] => {
    if ("text" in s) return split(s.text);
    if ("figure" in s) {
      const figure = figures.find((f) => f.id === s.figure);
      return figure ? [{ figure }] : [];
    }
    if ("table" in s) {
      const table = tables.find((t) => t.id === s.table);
      return table ? [{ table }] : [];
    }
    const quote = quotes.find((q) => q.id === s.quote);
    return quote ? [{ quote }] : [];
  });
}

/** The marker's accessible name: the figure as shown, then where it came from. */
export function figureName(f: Omit<Figure, "id">): string {
  switch (f.status) {
    case "sourced":
      return `${f.display}, sourced${f.source?.entity ? ` from ${f.source.entity}` : ""}`;
    case "computed": {
      const from = evidenceOf(f.computed?.of ?? []).join(", ");
      return `${f.display}, computed${from && ` from ${from}`}`;
    }
    default:
      return `${f.display}, ${f.status}`;
  }
}

/** Where a figure's cell is, as the popover lists it: entity, column, row key. */
export function sourceLabels({ source: s }: Omit<Figure, "id">): string[] {
  return [s?.entity, s?.column, s?.row_key].filter((l): l is string => !!l);
}

const INFIX: Record<string, string> = { add: "+", subtract: "−", multiply: "×", divide: "÷" };

/** "30 − 18"; an operation whose operands came without what they showed reads by its name. */
export function computedExpression(op: string, of: Operand[]): string {
  const values = of.map((o) => o.display);
  if (values.some((v) => v === undefined)) return op.replace("_", " ");
  if (INFIX[op]) return values.join(` ${INFIX[op]} `);
  return `${op.replace("_", " ")} of ${values.join(", ")}`;
}

/** Each evidence a computation read, once, in order. */
export const evidenceOf = (of: Operand[]): string[] => [...new Set(of.map((o) => o.evidence))];

/** The columns a computation read from one evidence: "granted, taken". */
export const columnsFrom = (of: Operand[], evidence: string): string =>
  of
    .filter((o) => o.evidence === evidence && o.column)
    .map((o) => o.column)
    .join(", ");

/** A tool call as the reader sees it: "read_ledger(order: income desc, limit: 5)". */
export function callLabel({ tool, args }: Call): string {
  const shown = Object.entries(args ?? {}).map(
    ([k, v]) => `${k}: ${typeof v === "string" ? v : JSON.stringify(v)}`,
  );
  return `${tool}(${shown.join(", ")})`;
}

/** A column as a table head reads it, in sentence case: "gross_salary" is "Gross salary". */
export function heading(column: string): string {
  const words = column.replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

// What the cell showed, its grouping commas aside: the audit keeps no value.
const isNumber = (c?: Cell) => {
  const v = (c?.display ?? "").replaceAll(",", "").trim();
  return v !== "" && Number.isFinite(Number(v));
};

/** For each column of a table, whether every one of its cells is a number: those align right. */
export const numericColumns = ({ columns, rows }: GroundedTable): boolean[] =>
  columns.map((_, i) => rows.length > 0 && rows.every((row) => isNumber(row[i])));
