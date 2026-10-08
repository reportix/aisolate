"use client";

import { type ReactNode, useId, useLayoutEffect, useRef, useState } from "react";
import { cx } from "../cx";
import {
  type Figure,
  type FigureStatus,
  type Grounding,
  type GroundedTable,
  type Quote,
  callLabel,
  columnsFrom,
  computedExpression,
  evidenceOf,
  figureName,
  heading,
  numericColumns,
  piecesOf,
  sourceLabels,
} from "../lib/provenance";

// The chat's marks, so an answer reads the same in the chat and in the audit. The underline says the
// state; the glyph after an unsourced or mismatched figure says it without colour.
const LOOK: Record<FigureStatus, string> = {
  sourced: "decoration-control decoration-dotted",
  computed: "decoration-control decoration-dotted",
  unsourced: "decoration-warn decoration-dotted",
  mismatch: "decoration-error decoration-wavy",
};
const GLYPH: Partial<Record<FigureStatus, string>> = { unsourced: "!", mismatch: "✕" };

/**
 * An answer from its segments, each figure, quote and table with its provenance marker.
 * The prose shows as typed (line breaks kept, no markdown); a markdown renderer when
 * answers need headings or lists.
 */
export function GroundedAnswer({ grounding }: { grounding: Grounding }) {
  return (
    <div className="text-sm leading-7 whitespace-pre-line text-ink">
      {piecesOf(grounding).map((p, i) =>
        "text" in p ? (
          <span key={i}>{p.text}</span>
        ) : "figure" in p ? (
          <FigureMark key={i} figure={p.figure} />
        ) : "quote" in p ? (
          <QuoteMark key={i} quote={p.quote} />
        ) : (
          <TableMark key={i} table={p.table} />
        ),
      )}
    </div>
  );
}

/** One figure, or a table's cell (`quiet`: a thinner underline), and where it came from. */
export function FigureMark({ figure: f, quiet }: { figure: Omit<Figure, "id">; quiet?: boolean }) {
  const glyph = GLYPH[f.status];
  return (
    <Mark
      name={figureName(f)}
      className={cx(
        "underline underline-offset-[3px]",
        quiet ? "decoration-1" : "decoration-[1.5px]",
        LOOK[f.status],
      )}
      label={
        <>
          {f.display}
          {glyph && (
            <span
              aria-hidden="true"
              className={cx(
                "ml-0.5 inline-block align-super text-[0.65em] font-semibold",
                f.status === "mismatch" ? "text-error" : "text-warn",
              )}
            >
              {glyph}
            </span>
          )}
        </>
      }
    >
      <FigureCard figure={f} />
    </Mark>
  );
}

/** A passage's words, quoted inline, and after them a marker naming the passage. */
export function QuoteMark({ quote: q }: { quote: Quote }) {
  const passage = [q.title, q.section].filter(Boolean).join(" · ") || `Passage ${q.index ?? ""}`;
  const mismatch = q.status === "mismatch";
  return (
    <>
      <q
        className={
          mismatch ? "underline decoration-error decoration-wavy underline-offset-[3px]" : undefined
        }
      >
        {q.text}
      </q>{" "}
      <Mark
        name={mismatch ? `Mismatch, not the words of ${passage}` : `Quoted from ${passage}`}
        className="text-xs text-ink-secondary underline decoration-control decoration-dotted underline-offset-[3px]"
        label={
          <>
            {mismatch && (
              <span aria-hidden="true" className="font-semibold text-error">
                ✕{" "}
              </span>
            )}
            {passage}
          </>
        }
      >
        <span className="block">
          {mismatch ? <Mismatch /> : <strong className="font-semibold">Quoted</strong>}
          {` · ${mismatch ? (q.reason ?? passage) : `the exact words of ${passage}`}`}
        </span>
        <span className="block text-ink-secondary">
          Evidence <Evidence id={q.evidence} />
          {q.index !== undefined && ` · passage ${q.index}`}
        </span>
      </Mark>
    </>
  );
}

/** A table of one evidence, a quiet marker on each cell, and under it the call that read its rows. */
export function TableMark({ table: t }: { table: GroundedTable }) {
  const numeric = numericColumns(t);
  const align = (i: number) => (numeric[i] ? "text-right" : "text-left");
  return (
    <figure className="my-3 whitespace-normal">
      <div className="overflow-x-auto rounded-lg border border-hairline">
        <table className="w-full border-collapse text-sm [&_tbody_tr]:border-t [&_tbody_tr]:border-hairline">
          <thead>
            <tr>
              {t.columns.map((c, i) => (
                <th
                  key={c}
                  scope="col"
                  className={cx("px-4 py-2.5 text-[13px] font-medium text-ink-secondary", align(i))}
                >
                  {heading(c)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {t.rows.map((row, r) => (
              <tr key={r}>
                {row.map((c, i) => (
                  <td key={i} className={cx("px-4 py-2.5 text-ink tabular-nums", align(i))}>
                    <FigureMark figure={c} quiet />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {(t.call || t.status === "mismatch") && (
        <figcaption className="mt-1.5 text-xs leading-4 text-ink-tertiary">
          {t.status === "mismatch" && (
            <>
              <Mismatch />
              {t.reason && ` · ${t.reason}`}
              {t.call && " · "}
            </>
          )}
          {t.call && `From ${callLabel(t.call)}`}
        </figcaption>
      )}
    </figure>
  );
}

const Mismatch = () => (
  <strong className="font-semibold text-error">
    <span aria-hidden="true">✕ </span>Mismatch
  </strong>
);

const Evidence = ({ id }: { id: string }) => <span className="text-ink">{id}</span>;

function FigureCard({ figure: f }: { figure: Omit<Figure, "id"> }) {
  const labels = sourceLabels(f);
  const source = f.source && (
    <span className="block text-ink-secondary">
      {f.status === "mismatch" && labels.length > 0 && `${labels.join(" · ")} · `}
      Evidence <Evidence id={f.source.evidence} />
    </span>
  );
  switch (f.status) {
    case "sourced":
      return (
        <>
          <span className="block">
            <strong className="font-semibold">Sourced</strong>
            {labels.length > 0 && ` · ${labels.join(" · ")}`}
          </span>
          {source}
        </>
      );
    case "computed": {
      const of = f.computed?.of ?? [];
      return (
        <span className="block">
          <strong className="font-semibold">Computed</strong>
          {labels.length > 0 && ` · ${labels.join(" · ")}`}
          {f.computed && ` · ${computedExpression(f.computed.op, of)}`}
          {of.length > 0 && " · from "}
          {evidenceOf(of).map((id, i) => (
            <span key={id}>
              {i > 0 && ", "}
              <Evidence id={id} />
              {columnsFrom(of, id) && ` (${columnsFrom(of, id)})`}
            </span>
          ))}
        </span>
      );
    }
    case "unsourced":
      return (
        <>
          <strong className="block font-semibold text-warn">
            <span aria-hidden="true">! </span>Unsourced
          </strong>
          <span className="block text-ink-secondary">
            {f.reason ?? "Typed by the model, not taken from the data"}
          </span>
        </>
      );
    case "mismatch":
      return (
        <>
          <span className="block">
            <Mismatch />
            {f.reason && ` · ${f.reason}`}
          </span>
          {source}
        </>
      );
  }
}

type MarkProps = { name: string; label: ReactNode; className: string; children: ReactNode };

/**
 * A focusable marker and its card. Hovering, focusing or tapping it opens the card under it;
 * Escape, blur, a tap elsewhere or leaving closes it, and the pointer can cross onto it.
 */
function Mark({ name, label, className, children }: MarkProps) {
  const cardId = useId();
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLSpanElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const card = useRef<HTMLSpanElement>(null);
  const leaving = useRef<ReturnType<typeof setTimeout>>(undefined);

  // Fixed, so a table's scroll box cannot clip it: under the marker, above it when it would not fit.
  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const b = button.current!.getBoundingClientRect();
      const c = card.current!;
      const below = b.bottom + 6 + c.offsetHeight <= window.innerHeight - 8;
      c.style.top = `${below ? b.bottom + 6 : b.top - 6 - c.offsetHeight}px`;
      c.style.left = `${Math.max(8, Math.min(b.left, window.innerWidth - c.offsetWidth - 8))}px`;
    };
    const away = (e: PointerEvent) => {
      if (!wrap.current?.contains(e.target as Node)) setOpen(false);
    };
    place();
    window.addEventListener("scroll", place, true);
    window.addEventListener("resize", place);
    document.addEventListener("pointerdown", away);
    return () => {
      window.removeEventListener("scroll", place, true);
      window.removeEventListener("resize", place);
      document.removeEventListener("pointerdown", away);
    };
  }, [open]);

  return (
    <span
      ref={wrap}
      onPointerEnter={(e) => {
        if (e.pointerType !== "mouse") return;
        clearTimeout(leaving.current);
        setOpen(true);
      }}
      onPointerLeave={(e) => {
        if (e.pointerType !== "mouse" || wrap.current?.contains(document.activeElement)) return;
        leaving.current = setTimeout(() => setOpen(false), 150);
      }}
      onBlur={(e) => {
        if (!wrap.current?.contains(e.relatedTarget as Node)) setOpen(false);
      }}
      onKeyDown={(e) => {
        if (e.key !== "Escape" || !open) return;
        e.stopPropagation();
        button.current?.focus();
        setOpen(false);
      }}
    >
      <button
        ref={button}
        type="button"
        aria-label={name}
        aria-expanded={open}
        aria-describedby={cardId}
        onFocus={() => setOpen(true)}
        onClick={() => setOpen(true)}
        className={className}
      >
        {label}
      </button>
      <span
        ref={card}
        id={cardId}
        hidden={!open}
        className="fixed top-0 left-0 z-50 block w-max max-w-[min(28rem,calc(100vw-16px))] rounded-lg border border-hairline bg-paper px-3 py-2 text-left font-sans text-xs leading-5 whitespace-normal text-ink shadow-overlay"
      >
        {children}
      </span>
    </span>
  );
}
