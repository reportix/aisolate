"use client";

import { useId, useState } from "react";
import { type Line, short, slotOf, ticks } from "@/lib/metrics";

const W = 520;
const H = 200;
const PAD = { top: 8, right: 12, bottom: 24, left: 44 };

type Props = {
  lines: Line[];
  /** Every airlock key the reader sees, in a fixed order: a line's colour follows its airlock. */
  keys: readonly string[];
  from: number;
  to: number;
  unit: string;
  /** Says how to read a value: "1.5 exchanges per minute". */
  format?: (v: number) => string;
};

const keyOf = (l: Pick<Line, "space" | "airlock">) => `${l.space}/${l.airlock}`;
const CLOCK = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" });
const DAY = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" });

/**
 * Lines over time on one axis: 2px lines in the airlock's hue, recessive gridlines, the legend
 * under it for two or more, a crosshair with every line's value under the pointer.
 */
export function LineChart({ lines, keys, from, to, unit, format = short }: Props) {
  const [hover, setHover] = useState<number | null>(null);
  const id = useId();
  if (lines.length === 0) {
    return <p className="px-4 py-3 text-sm text-ink-tertiary">Nothing in this period.</p>;
  }
  const max = Math.max(...lines.flatMap((l) => l.points.map((p) => p[1])));
  const yTicks = ticks(max);
  const top = yTicks.at(-1)!;
  const span = Math.max(to - from, 1);
  const x = (t: number) => PAD.left + ((t - from) / span) * (W - PAD.left - PAD.right);
  const y = (v: number) => H - PAD.bottom - (v / top) * (H - PAD.top - PAD.bottom);
  const days = span > 2 * 86400;
  const xTicks = [0, 1, 2, 3].map((i) => from + (span * i) / 3);
  const times = [...new Set(lines.flatMap((l) => l.points.map((p) => p[0])))].sort((a, b) => a - b);
  const at = hover === null ? null : times[hover];

  const onMove = (e: React.PointerEvent<SVGRectElement>) => {
    const box = e.currentTarget.getBoundingClientRect();
    const t = from + ((e.clientX - box.left) / box.width) * span;
    let best = 0;
    times.forEach((tt, i) => Math.abs(tt - t) < Math.abs(times[best] - t) && (best = i));
    setHover(best);
  };

  return (
    <div className="grid gap-2 px-4 pt-3 pb-3">
      <div className="relative">
        <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img" aria-labelledby={`${id}-desc`}>
          <desc
            id={`${id}-desc`}
          >{`${lines.length} lines, ${unit}; the table below holds the values.`}</desc>
          {yTicks.map((v) => (
            <g key={v}>
              <line
                x1={PAD.left}
                x2={W - PAD.right}
                y1={y(v)}
                y2={y(v)}
                className="stroke-hairline"
              />
              <text
                x={PAD.left - 6}
                y={y(v)}
                dy="0.32em"
                textAnchor="end"
                className="fill-ink-tertiary text-[10px]"
              >
                {short(v)}
              </text>
            </g>
          ))}
          {xTicks.map((t, i) => (
            <text
              key={t}
              x={x(t)}
              y={H - 6}
              textAnchor={i === 0 ? "start" : i === 3 ? "end" : "middle"}
              className="fill-ink-tertiary text-[10px]"
            >
              {(days ? DAY : CLOCK).format(new Date(t * 1000))}
            </text>
          ))}
          {lines.map((l) => (
            <path
              key={keyOf(l)}
              d={l.points
                .map((p, i) => `${i ? "L" : "M"}${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`)
                .join("")}
              fill="none"
              strokeWidth={2}
              strokeLinejoin="round"
              strokeLinecap="round"
              style={{ stroke: `var(--series-${slotOf(keyOf(l), keys)})` }}
            />
          ))}
          {at !== null && (
            <g>
              <line
                x1={x(at)}
                x2={x(at)}
                y1={PAD.top}
                y2={H - PAD.bottom}
                className="stroke-control"
              />
              {lines.map((l) => {
                const p = l.points.find((pp) => pp[0] === at);
                return (
                  p && (
                    <circle
                      key={keyOf(l)}
                      cx={x(p[0])}
                      cy={y(p[1])}
                      r={4}
                      strokeWidth={2}
                      className="stroke-paper"
                      style={{ fill: `var(--series-${slotOf(keyOf(l), keys)})` }}
                    />
                  )
                );
              })}
            </g>
          )}
          <rect
            x={PAD.left}
            y={PAD.top}
            width={W - PAD.left - PAD.right}
            height={H - PAD.top - PAD.bottom}
            fill="transparent"
            onPointerMove={onMove}
            onPointerLeave={() => setHover(null)}
          />
        </svg>
        {at !== null && (
          <div
            className="pointer-events-none absolute top-0 z-10 grid gap-1 rounded-lg border border-hairline bg-paper px-3 py-2 text-xs shadow-overlay"
            style={{
              left: `${(x(at) / W) * 100}%`,
              transform: x(at) > W / 2 ? "translateX(calc(-100% - 8px))" : "translateX(8px)",
            }}
          >
            <span className="text-ink-tertiary">
              {(days ? DAY : CLOCK).format(new Date(at * 1000))}
            </span>
            {lines.map((l) => {
              const p = l.points.find((pp) => pp[0] === at);
              return (
                p && (
                  <span key={keyOf(l)} className="flex items-center gap-2 whitespace-nowrap">
                    <Swatch slot={slotOf(keyOf(l), keys)} />
                    <span className="text-ink-secondary">{`${l.space} / ${l.airlock}`}</span>
                    <span className="ml-auto pl-3 font-medium text-ink">{format(p[1])}</span>
                  </span>
                )
              );
            })}
          </div>
        )}
      </div>
      {lines.length > 1 && (
        <ul
          className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-secondary"
          aria-label="Legend"
        >
          {lines.map((l) => (
            <li key={keyOf(l)} className="flex items-center gap-1.5">
              <Swatch slot={slotOf(keyOf(l), keys)} />
              {`${l.space} / ${l.airlock}`}
            </li>
          ))}
        </ul>
      )}
      {/* A table grows to its rows whatever its height: hidden in a box that does not. */}
      <div className="sr-only">
        <table>
          <caption>{unit}</caption>
          <thead>
            <tr>
              <th scope="col">Time</th>
              {lines.map((l) => (
                <th key={keyOf(l)} scope="col">{`${l.space} / ${l.airlock}`}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {times.map((t) => (
              <tr key={t}>
                <th scope="row">{new Date(t * 1000).toISOString()}</th>
                {lines.map((l) => (
                  <td key={keyOf(l)}>{format(l.points.find((p) => p[0] === t)?.[1] ?? 0)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Swatch({ slot }: { slot: number }) {
  return (
    <span
      aria-hidden
      className="inline-block h-0.5 w-3 shrink-0 rounded-full"
      style={{ background: `var(--series-${slot})` }}
    />
  );
}
