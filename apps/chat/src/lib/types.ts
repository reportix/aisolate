/** Why a tool call could not read its data, as the airlock tells it: never the cause itself. */
export type ToolError = "login_refused" | "unreachable" | "failed";

export type ToolCallInfo = {
  id: string;
  tool: string;
  args: Record<string, unknown>;
  done: boolean;
  entity?: string;
  error?: ToolError;
};

export type Evidence = {
  id: string;
  /** The tool call this evidence answers; two calls to the same tool are told apart by it. */
  call_id: string;
  tool: string;
  entity: string;
  row_count: number;
  columns: string[];
  rule_version: string;
  rules: string[];
  sql_hash: string;
  /** What the columns mean, as the tool's labels name them: the row key column finds a figure's row. */
  labels?: { row_key?: string | null };
  /** The first rows as the tool returned them, at most ten; the model saw them all. */
  rows?: Record<string, unknown>[];
  /** A document search's passages, as the model read them. */
  passages?: Passage[];
};

export type Passage = { title: string; section: string; body: string };

/** Where a figure in the answer came from: copied from a cell, computed from cells, typed by the model, or contradicted. */
export type FigureStatus = "sourced" | "computed" | "unsourced" | "mismatch";

/** One cell as the airlock resolved it, with the labels the entity's metadata gives its row. */
export type FigureSource = {
  evidence: string;
  entity?: string;
  column?: string;
  row_key?: string;
  subject?: string;
  period?: string;
  unit?: string;
};

/** An operand of a computed figure, or a ranked row: the cell it reads and, once resolved, its value. */
export type Operand = FigureSource & { value?: string; display?: string };

/** An operation AIsolate evaluated: over cells (`of`), or a ranking or extreme of one result (`by`). */
export type Computed = {
  op: string;
  of?: Operand[];
  n?: number;
  by?: { evidence: string; entity?: string; column?: string };
  rows?: Operand[];
  row?: Operand;
  call?: Call;
};

/** The model's own claim of what a figure is about; a conflict with the row's labels is a mismatch. */
export type About = { subject?: string; period?: string; concept?: string; unit?: string };

export type Figure = {
  id: string;
  /** As the reader sees it, formatted by the airlock: "78,000 EUR". */
  display: string;
  value?: string;
  status: FigureStatus;
  source?: FigureSource;
  computed?: Computed;
  /** Why a figure is a mismatch, in plain words. */
  reason?: string;
  about?: About;
};

/** One cell of a grounded table: a figure without an id of its own. */
export type Cell = Pick<Figure, "display" | "value" | "status" | "source">;

/** The tool call an evidence came from, as the `tool_call` event showed it. */
export type Call = { tool: string; args: Record<string, unknown> };

/** Rows of one evidence, each cell sourced; a mismatch when the rows are not what they claim to be. */
export type Table = {
  id: string;
  status: "sourced" | "mismatch";
  evidence?: string;
  entity?: string;
  call?: Call;
  columns: string[];
  rows: Cell[][];
  reason?: string;
};

/** A passage's exact words, or a mismatch when they are not. */
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

/** The answer in order: prose (markdown) and the figures, tables and quotes between it. */
export type Segment = { text: string } | { figure: string } | { table: string } | { quote: string };

export type Provenance = {
  status: "all_sourced" | "has_unsourced" | "has_mismatch" | "no_figures";
  sourced: number;
  computed: number;
  unsourced: number;
  mismatch: number;
};

/** What the final event adds when the airlock grounded the answer's figures. */
export type Grounding = {
  segments: Segment[];
  figures: Figure[];
  tables?: Table[];
  quotes?: Quote[];
  provenance?: Provenance;
};

/** A verifier's word on an answer; the chat shows its note as the verdict line. */
export type Verdict = { handler: string; status: "PASS" | "WARN" | "FAIL"; note?: string };

export type StreamEvent =
  | { type: "tool_call"; id: string; tool: string; args: Record<string, unknown> }
  | ({ type: "evidence" } & Evidence)
  | { type: "tool_error"; call_id: string; tool: string; error: ToolError }
  | { type: "answer_chunk"; content: string }
  | { type: "verification"; verdicts: Verdict[] }
  | ({ type: "done"; evidence_ids: string[]; grounded: boolean } & Partial<Grounding>)
  | { type: "error"; message: string };

export type MessageStatus = "streaming" | "done" | "stopped" | "error";

export type Message = {
  id: string;
  role: "user" | "assistant";
  content: string;
  toolCalls: ToolCallInfo[];
  evidence: Evidence[];
  status: MessageStatus;
  grounded?: boolean;
  verdicts?: Verdict[];
  error?: string;
} & Partial<Grounding>;
