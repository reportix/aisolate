"use client";

import { useId, useLayoutEffect, useRef, useState } from "react";
import { type Context, evidenceOfFigure, figureCard, quoteCard } from "@/lib/provenance";
import type { Figure, Quote } from "@/lib/types";

type Tone = "source" | "warn" | "error";

// The source's quiet chip; amber and red only for a problem, which also carries a "!".
const TONE: Record<Tone, string> = {
  source: "bg-mark-wash text-mark hover:bg-mark-wash-strong aria-expanded:bg-mark-wash-strong",
  warn: "bg-warn-wash text-warn ring-1 ring-inset ring-warn-border",
  error: "bg-error-wash text-error ring-1 ring-inset ring-error-border",
};

type MarkProps = { numbers: number[]; tone: Tone; card: string };

const nameOf = ({ numbers, tone }: Omit<MarkProps, "card">) => {
  const sources =
    numbers.length === 0
      ? ""
      : `${numbers.length === 1 ? "Source" : "Sources"} ${numbers.join(" and ")}`;
  if (tone === "source") return sources;
  const problem = tone === "warn" ? "not in the data" : "does not match";
  return sources ? `${sources}, ${problem}` : capital(problem);
};
const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/**
 * A numbered source marker and its card. Hovering, focusing or tapping it opens the card; Escape,
 * blur, a tap elsewhere or leaving closes it, and the pointer can cross onto it.
 */
export function SourceMark({ numbers, tone, card: text }: MarkProps) {
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
      className="whitespace-nowrap"
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
        aria-label={nameOf({ numbers, tone })}
        aria-expanded={open}
        aria-describedby={cardId}
        onFocus={() => setOpen(true)}
        onClick={() => setOpen(true)}
        className={`ml-1 inline-flex h-[1.125rem] min-w-[1.125rem] -translate-y-px items-center justify-center gap-0.5 rounded-full px-1.5 align-middle font-sans text-[0.6875rem] leading-none font-semibold tabular-nums transition-colors ${TONE[tone]}`}
      >
        {tone !== "source" ? (
          <span aria-hidden="true">!</span>
        ) : (
          numbers.length > 0 && <span aria-hidden="true">{numbers.join(",")}</span>
        )}
      </button>
      <span
        ref={card}
        id={cardId}
        role="tooltip"
        hidden={!open}
        className="fixed top-0 left-0 z-50 block w-max max-w-[min(26rem,calc(100vw-16px))] rounded-xl border border-hairline bg-paper px-3.5 py-2.5 text-left font-sans text-[0.8125rem] leading-5 font-normal whitespace-normal text-ink shadow-[var(--shadow-overlay)]"
      >
        {tone !== "source" && (
          <span aria-hidden="true" className={`font-semibold ${TONE_TEXT[tone]}`}>
            !{" "}
          </span>
        )}
        {text}
      </span>
    </span>
  );
}

const TONE_TEXT: Record<Tone, string> = { source: "", warn: "text-warn", error: "text-error" };

/** The marker of a figure, a quote or a table: its sources' numbers, amber or red for a problem. */
export function markOf(status: string, evidence: string[], ctx: Context): Omit<MarkProps, "card"> {
  const numbers = evidence
    .map(ctx.number)
    .filter((n): n is number => n !== undefined)
    .sort((a, b) => a - b);
  const tone = status === "unsourced" ? "warn" : status === "mismatch" ? "error" : "source";
  return { numbers, tone };
}

/** One figure of an answer: bold, and after it the marker of where it came from. */
export function FigureMark({ figure: f, ctx }: { figure: Omit<Figure, "id">; ctx: Context }) {
  return (
    <>
      <strong className="font-semibold text-ink">{f.display}</strong>
      <SourceMark {...markOf(f.status, evidenceOfFigure(f), ctx)} card={figureCard(f, ctx)} />
    </>
  );
}

/** A passage's words, quoted, and after them the document's marker. */
export function QuoteMark({ quote: q, ctx }: { quote: Quote; ctx: Context }) {
  return (
    <>
      <q>{q.text}</q>
      <SourceMark {...markOf(q.status, [q.evidence], ctx)} card={quoteCard(q, ctx)} />
    </>
  );
}
