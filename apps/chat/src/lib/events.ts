import { groundingOf } from "./provenance";
import type { Evidence, Message, StreamEvent } from "./types";

/** Pure reducer: folds one SSE event into the streaming assistant message. */
export function reduce(m: Message, e: StreamEvent): Message {
  switch (e.type) {
    case "tool_call":
      return {
        ...m,
        status: "streaming",
        toolCalls: [...m.toolCalls, { id: e.id, tool: e.tool, args: e.args ?? {}, done: false }],
      };
    case "evidence": {
      const evidence: Evidence = {
        id: e.id,
        call_id: e.call_id,
        tool: e.tool,
        entity: e.entity,
        row_count: e.row_count,
        columns: e.columns,
        rule_version: e.rule_version,
        rules: e.rules,
        sql_hash: e.sql_hash,
        ...(e.labels && { labels: e.labels }),
        ...(Array.isArray(e.rows) && { rows: e.rows }),
        ...(Array.isArray(e.passages) && { passages: e.passages }),
      };
      // By call id, not by tool name: one turn can call the same tool more than once.
      const toolCalls = [...m.toolCalls];
      const i = toolCalls.findIndex((t) => t.id === evidence.call_id);
      if (i >= 0) toolCalls[i] = { ...toolCalls[i], done: true, entity: evidence.entity };
      return { ...m, status: "streaming", toolCalls, evidence: [...m.evidence, evidence] };
    }
    case "tool_error":
      return {
        ...m,
        toolCalls: m.toolCalls.map((t) =>
          t.id === e.call_id ? { ...t, done: true, error: e.error } : t,
        ),
      };
    case "answer_chunk":
      return { ...m, content: m.content + (e.content ?? "") };
    case "verification":
      return { ...m, verdicts: e.verdicts ?? [] };
    case "done":
      return { ...m, status: "done", grounded: e.grounded, ...groundingOf(e) };
    case "error":
      return { ...m, status: "error", error: e.message };
    default:
      // An event this build does not know about must not break the stream.
      return m;
  }
}
