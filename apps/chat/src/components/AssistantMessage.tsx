"use client";

import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import type { Element } from "hast";
import type { ReactNode } from "react";
import type { Grounding, Message, Table } from "@/lib/types";
import { stripCitations } from "@/lib/citations";
import {
  answerMarkdown,
  careLine,
  citedEvidence,
  type Context,
  contextOf,
  heading,
  numericColumns,
  rehypeMarks,
  sourceRows,
  tableCard,
} from "@/lib/provenance";
import { checkLine, OUTCOMES } from "@/lib/trail";
import { FigureMark, markOf, QuoteMark, SourceMark } from "./FigureMark";
import { PendingDot } from "./PendingDot";
import { Sources } from "./Sources";

// The answer's card: 12px corners, a hairline, sentence-case heads; lines only between rows.
const card = "overflow-x-auto rounded-xl border border-hairline";
const grid = "w-full border-collapse text-sm [&_tbody_tr]:border-t [&_tbody_tr]:border-hairline";
// Figures in the text's own font, tabular so their digits line up; a numeric column aligns right.
const head = "px-4 py-2.5 text-[13px] font-medium text-ink-secondary";
const cell = "px-4 py-2.5 tabular-nums text-ink";
const align = (numeric: boolean) => (numeric ? "text-right" : "text-left");

// Links open safely; a table keeps the alignment its markdown asks for. An image shows its alt text
// only: fetching its URL on render would hand whatever the answer put in it to that host, unclicked.
const components: Components = {
  img: ({ alt }) => <>{alt}</>,
  a: ({ href, children }) => (
    <a href={href} target="_blank" rel="noopener noreferrer" className="text-pen underline">
      {children}
    </a>
  ),
  p: ({ children }) => <p className="mb-3 last:mb-0">{children}</p>,
  strong: ({ children }) => <strong className="font-semibold text-ink">{children}</strong>,
  table: ({ children }) => (
    <div className={`mb-3 ${card}`}>
      <table className={grid}>{children}</table>
    </div>
  ),
  th: ({ children, style }) => (
    <th className={`${head} text-left`} style={style}>
      {children}
    </th>
  ),
  td: ({ children, style }) => (
    <td className={cell} style={style}>
      {children}
    </td>
  ),
};

// A reasoning model's thinking never shows, not even while it streams before its </think>.
const stripThinking = (text: string) => text.replace(/<think>[\s\S]*?(<\/think>|$)/g, "").trim();

export function AssistantMessage({
  message: m,
  userName,
}: {
  message: Message;
  userName?: string;
}) {
  const { segments, figures, tables = [], quotes = [] } = m;
  const grounded = segments && figures && { segments, figures, tables, quotes };
  const markdown = grounded ? answerMarkdown(grounded) : "";
  // A grounded answer renders from its segments; one without them (older answers), as it streamed.
  const text = stripCitations(stripThinking(grounded ? markdown : m.content));
  const rows = sourceRows(m, grounded ? citedEvidence(markdown, grounded) : [], userName);
  const ctx = contextOf(rows);
  const marks: Components = grounded
    ? {
        ...components,
        span: ({ node, children }) => marked(grounded, node, ctx) ?? <span>{children}</span>,
        div: ({ node, children }) => marked(grounded, node, ctx) ?? <div>{children}</div>,
      }
    : components;
  const checked = grounded && m.provenance;
  const care = checked && careLine({ ...grounded, provenance: m.provenance });
  const streaming = m.status === "streaming";
  const waiting = streaming && text === "";
  const unsupported =
    m.status === "done" &&
    m.grounded === false &&
    m.toolCalls.length === 0 &&
    m.evidence.length === 0;
  // The verifiers' word and the no-evidence note: under the answer, or under Sources when the answer has a problem.
  const trail = (
    <>
      {m.verdicts?.map((v, i) => (
        // A note doesn't say its status: a verdict that isn't a pass is marked like the care line.
        <p
          key={v.handler}
          className={`${i === 0 && !care ? "mt-3" : "mt-1"} text-xs text-ink-tertiary`}
        >
          {v.status !== "PASS" && (
            <>
              <span
                aria-hidden="true"
                className={`font-semibold ${v.status === "FAIL" ? "text-error" : "text-warn"}`}
              >
                !{" "}
              </span>
              <span className="sr-only">{OUTCOMES[v.status] ?? v.status}. </span>
            </>
          )}
          {checkLine(v)}
        </p>
      ))}
      {unsupported && (
        // The model answered without touching any tool or document: say so, quietly.
        <p className="mt-3 text-xs text-ink-tertiary">
          <span aria-hidden="true">◌ </span>No evidence for this answer
        </p>
      )}
    </>
  );
  return (
    <article>
      {waiting ? (
        <p className="flex items-center gap-2 text-sm text-ink-tertiary">
          <PendingDot />
          Thinking…
        </p>
      ) : (
        text !== "" && (
          <div className="answer">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              rehypePlugins={grounded ? [rehypeMarks] : []}
              components={marks}
            >
              {text}
            </ReactMarkdown>
          </div>
        )
      )}
      {care && !care.ok && (
        // The one fixed line for a problem; the why is on the chips and under Sources.
        <p className="mt-3 text-xs text-ink-secondary">
          <span aria-hidden="true" className="font-semibold text-error">
            !{" "}
          </span>
          <span className="font-semibold text-ink">
            {care.text.slice(0, care.text.indexOf(". ") + 1)}
          </span>
          {care.text.slice(care.text.indexOf(". ") + 1)}
        </p>
      )}
      <Sources rows={rows} trail={care && !care.ok ? trail : undefined} />
      {care?.ok && (
        // The figures' provenance, a quiet line at the answer's end.
        <p className="mt-3 text-xs text-ink-tertiary">
          <span aria-hidden="true" className="font-semibold text-ok">
            ✓{" "}
          </span>
          {care.text}
        </p>
      )}
      {!(care && !care.ok) && trail}
      {m.status === "stopped" && <p className="mt-2 text-xs text-ink-tertiary">Stopped.</p>}
      {m.status === "error" && (
        <p className="mt-2 text-xs text-ink-tertiary">
          Request failed{m.error ? `: ${m.error}` : "."}
        </p>
      )}
    </article>
  );
}

/** The figure, quote or table a mark (`data-mark="f0"`) stands for; nothing for any other element. */
function marked(
  { figures, tables = [], quotes = [] }: Grounding,
  node: Element | undefined,
  ctx: Context,
): ReactNode {
  const mark = node?.properties?.dataMark;
  if (typeof mark !== "string") return null;
  const i = Number(mark.slice(1));
  switch (mark[0]) {
    case "f":
      return <FigureMark figure={figures[i]} ctx={ctx} />;
    case "q":
      return <QuoteMark quote={quotes[i]} ctx={ctx} />;
    default:
      return <GroundedTable table={tables[i]} ctx={ctx} />;
  }
}

/** A table of one evidence, its cells plain, and under it the one marker of where its rows came from. */
function GroundedTable({ table: t, ctx }: { table: Table; ctx: Context }) {
  const numeric = numericColumns(t);
  return (
    <figure className="mb-3">
      <div className={card}>
        <table className={grid}>
          <thead>
            <tr>
              {t.columns.map((c, i) => (
                <th key={c} scope="col" className={`${head} ${align(numeric[i])}`}>
                  {heading(c)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {t.rows.map((row, r) => (
              <tr key={r}>
                {row.map((c, i) => (
                  <td key={i} className={`${cell} ${align(numeric[i])}`}>
                    {c.display}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <figcaption className="mt-1.5 text-xs text-ink-tertiary">
        {t.status === "mismatch"
          ? "These rows need care"
          : `${t.rows.length} ${t.rows.length === 1 ? "row" : "rows"}`}
        <SourceMark
          {...markOf(t.status, t.evidence ? [t.evidence] : [], ctx)}
          card={tableCard(t, ctx)}
        />
      </figcaption>
    </figure>
  );
}
