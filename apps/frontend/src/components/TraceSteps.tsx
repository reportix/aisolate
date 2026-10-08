"use client";

import { CpuIcon, DatabaseIcon, DotIcon, WrenchIcon, cx, tableFrame } from "@aisolate/ui";
import { useState } from "react";
import type { AuditLine } from "@/lib/audit";
import { CopyButton } from "./CopyButton";
import { type Look, type Row, detailsOf, duration, extentOf } from "@/lib/trace";

const ICON = { model: CpuIcon, tool: WrenchIcon, data: DatabaseIcon, step: DotIcon };

/** A look's icon tile and bar: the hue as a wash behind its icon, and the bar in the hue. */
const TILE: Record<Look, string> = {
  model: "bg-trace-model/12 text-trace-model",
  tool: "bg-trace-tool/12 text-trace-tool",
  data: "bg-trace-data/15 text-trace-data",
  step: "bg-panel text-ink-secondary",
};
const BAR: Record<Look, string> = {
  model: "bg-trace-model/70",
  tool: "bg-trace-tool",
  data: "bg-trace-data",
  step: "bg-control/50",
};

function StepIcon({ look }: { look: Look }) {
  const Icon = ICON[look];
  return (
    <span
      className={cx("grid size-[22px] shrink-0 place-items-center rounded-md", TILE[look])}
      aria-hidden
    >
      <Icon size={14} />
    </span>
  );
}

/** The step's name at its depth, 18px further in per level. */
function Name({ row, extra }: { row: Row; extra?: string }) {
  return (
    <div className="flex min-w-0 items-center gap-2" style={{ paddingLeft: row.depth * 18 }}>
      <StepIcon look={row.look} />
      <span className="truncate">
        {row.label}
        {extra && <span className="ml-1 text-xs text-ink-tertiary">{extra}</span>}
      </span>
      {row.step.error && <span className="sr-only">(failed)</span>}
    </div>
  );
}

/** Where a step lies on the trace's time axis, as a left offset and a width in percent. */
function place(row: Row, extent: { start: number; total: number }, min: number) {
  return {
    left: `${((row.step.start_us - extent.start) / extent.total) * 100}%`,
    width: `max(${(row.step.duration_us / extent.total) * 100}%, ${min}px)`,
  };
}

type Props = { rows: Row[]; event: AuditLine };

/** The steps as a tree beside the selected one's details, or on a time axis. */
export function TraceSteps({ rows, event }: Props) {
  const [view, setView] = useState<"steps" | "timeline">("steps");
  // The exchange first: its question and answer, as MLflow opens a trace on its root span.
  const [selected, setSelected] = useState(0);
  const extent = extentOf(rows);

  return (
    <section className="grid min-w-0 gap-3">
      <div className="flex min-h-8 items-center justify-between gap-4">
        <h2 className="text-base font-semibold tracking-snug">Steps</h2>
        <div
          role="group"
          aria-label="View"
          className="inline-flex rounded border border-hairline-strong"
        >
          {(["steps", "timeline"] as const).map((v) => (
            <button
              key={v}
              type="button"
              aria-pressed={view === v}
              onClick={() => setView(v)}
              className={cx(
                "h-8 px-3.5 text-sm transition-colors duration-150 first:rounded-l last:rounded-r",
                view === v
                  ? "bg-panel font-medium text-ink"
                  : "bg-paper text-ink-secondary hover:text-ink",
              )}
            >
              {v === "steps" ? "Steps" : "Timeline"}
            </button>
          ))}
        </div>
      </div>
      <div className={cx("min-w-0 overflow-hidden", tableFrame)}>
        {view === "steps" ? (
          <div className="grid lg:grid-cols-[minmax(360px,44%)_1fr]">
            <ol aria-label="Steps" className="border-hairline py-1.5 lg:border-r">
              {rows.map((row, i) => (
                <li key={row.step.id}>
                  <button
                    type="button"
                    aria-current={i === selected ? "true" : undefined}
                    onClick={() => setSelected(i)}
                    className={cx(
                      "grid w-full grid-cols-[1fr_72px_96px] items-center gap-2.5 px-3.5 py-1.5 text-left text-sm",
                      i === selected
                        ? "bg-focus/8 shadow-[inset_2px_0_0_var(--color-focus)]"
                        : "hover:bg-desk",
                    )}
                  >
                    <Name row={row} />
                    <span
                      className={cx(
                        "text-right text-xs",
                        row.step.error ? "text-error" : "text-ink-secondary",
                      )}
                    >
                      {duration(row.step.duration_us)}
                    </span>
                    <span className="relative h-1.5 rounded-full bg-panel" aria-hidden>
                      <i
                        className={cx("absolute top-0 h-1.5 rounded-full", BAR[row.look])}
                        style={place(row, extent, 2)}
                      />
                    </span>
                  </button>
                </li>
              ))}
            </ol>
            {rows[selected] && (
              <StepDetails rows={rows} index={selected} event={event} extent={extent} />
            )}
          </div>
        ) : (
          <Timeline rows={rows} extent={extent} />
        )}
      </div>
    </section>
  );
}

function StepDetails({
  rows,
  index,
  event,
  extent,
}: Props & { index: number; extent: { start: number; total: number } }) {
  const row = rows[index];
  const { kind, facts, args, io } = detailsOf(rows, index, event);
  const all: [string, string][] = [
    ["Kind", kind],
    ...facts,
    ["Started", `+${duration(row.step.start_us - extent.start)}`],
    ["Took", duration(row.step.duration_us)],
  ];
  return (
    <div className="grid content-start gap-4 border-t border-hairline px-5 py-4.5 lg:border-t-0">
      <h3 className="flex items-center gap-2 text-base font-semibold">
        <StepIcon look={row.look} />
        {row.label}
      </h3>
      {io && <ExchangeIo io={io} />}
      <dl className="grid grid-cols-[140px_1fr] rounded-lg border border-hairline text-sm [&>*:nth-last-child(-n+2)]:border-b-0">
        {all.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="border-b border-hairline px-3.5 py-2.5 text-ink-secondary">{label}</dt>
            <dd
              className={cx(
                "min-w-0 border-b border-hairline px-3.5 py-2.5 break-words",
                label === "Error" && "text-error",
              )}
            >
              {value}
            </dd>
          </div>
        ))}
      </dl>
      {args && (
        <div className="grid gap-1.5">
          <span className="text-xs text-ink-tertiary">Arguments, from the audit record</span>
          <pre className="overflow-auto rounded-lg border border-hairline bg-desk px-3.5 py-3 font-mono text-xs leading-5">
            {args}
          </pre>
        </div>
      )}
    </div>
  );
}

/** The exchange's question and answer as JSON, as MLflow shows a root span's inputs and outputs. */
export function ExchangeIo({ io }: { io: { inputs: string; outputs: string } }) {
  return (
    <>
      <Json label="Inputs" json={io.inputs} />
      <Json label="Outputs" json={io.outputs} />
      <p className="-mt-2 text-xs text-ink-tertiary">
        From the audit record; the trace itself holds no question, answer or rows.
      </p>
    </>
  );
}

function Json({ label, json }: { label: string; json: string }) {
  return (
    <div className="grid gap-1.5">
      <div className="flex items-center justify-between">
        <span className="text-xs text-ink-tertiary">{label}</span>
        <CopyButton text={json} />
      </div>
      <pre className="overflow-auto rounded-lg border border-hairline bg-desk px-3.5 py-3 font-mono text-xs leading-5 whitespace-pre-wrap">
        {json}
      </pre>
    </div>
  );
}

const QUARTERS = [0, 0.25, 0.5, 0.75, 1];

/** The steps on one time axis, a quarter of the trace between gridlines. */
function Timeline({ rows, extent }: { rows: Row[]; extent: { start: number; total: number } }) {
  return (
    <div className="overflow-x-auto pt-2 pb-3.5">
      <div className="min-w-[720px]">
        <div className="grid grid-cols-[300px_1fr] px-3.5 pt-1 pb-2 text-xs text-ink-tertiary">
          <div />
          <div className="flex justify-between">
            {QUARTERS.map((q) => (
              <span key={q}>{q === 0 ? "0" : duration(extent.total * q)}</span>
            ))}
          </div>
        </div>
        <ol aria-label="Timeline">
          {rows.map((row) => (
            <li
              key={row.step.id}
              className="grid grid-cols-[300px_1fr] items-center px-3.5 py-1 text-sm"
            >
              <Name row={row} extra={duration(row.step.duration_us)} />
              <span className="relative h-4" aria-hidden>
                {QUARTERS.map((q) => (
                  <span
                    key={q}
                    className="absolute inset-y-0 w-px bg-hairline"
                    style={{ left: `calc(${q * 100}% - ${q}px)` }}
                  />
                ))}
                <i
                  className={cx("absolute top-[3px] h-2.5 rounded-[3px]", BAR[row.look])}
                  style={place(row, extent, 2)}
                />
              </span>
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}
