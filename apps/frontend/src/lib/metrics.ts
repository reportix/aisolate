import type { Period } from "./period";

// The shape of the backend's metrics endpoint (docs/api.md, GET /v1/metrics).

/** One line of a time chart; points are [epoch seconds, value]. */
export type Line = { space: string; airlock: string; points: [number, number][] };

/** One bar: whose it is, what it counts (a refusal's reason, a tool), how many over the period. */
export type Bar = { space: string; airlock: string; name: string; count: number };

export type Charts = {
  from: string;
  to: string;
  step_seconds: number;
  exchanges_per_minute: Line[];
  tokens_per_minute: Line[];
  p95_duration_seconds: Line[];
  refusals: Bar[];
  tool_calls: Bar[];
};

/** The charts' periods: the Tracing tab's but for all time, which Prometheus does not keep. */
export type MetricsPeriod = Exclude<Period, "all">;

export type MetricsFilters = { space?: string; airlock?: string; period?: MetricsPeriod };

export function metricsQuery(filters: MetricsFilters): string {
  const q = new URLSearchParams();
  for (const k of ["space", "airlock"] as const) if (filters[k]) q.set(k, filters[k]!);
  q.set("period", filters.period ?? "24h");
  return `?${q}`;
}

const SERIES = 8;

/**
 * A line's colour slot, 1 to 8: the airlock's place among every airlock the reader sees, so a
 * filter never repaints the ones that stay. Past eight the slots repeat; the legend tells them apart.
 */
export function slotOf(key: string, keys: readonly string[]): number {
  const i = keys.indexOf(key);
  return ((i < 0 ? keys.length : i) % SERIES) + 1;
}

const compact = new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 });
const plain = new Intl.NumberFormat("en", { maximumFractionDigits: 2 });

/** An axis tick or a value: 1.5, 12, 1.2K. */
export const short = (v: number) => (Math.abs(v) >= 1000 ? compact.format(v) : plain.format(v));

/** Round ticks from 0 to just above `max`: 0, 0.5, 1, 1.5 or 0, 200, 400. */
export function ticks(max: number, count = 4): number[] {
  if (!(max > 0)) return [0, 1];
  const raw = max / count;
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= raw)!;
  const top = Math.ceil(max / step) * step;
  return Array.from({ length: Math.round(top / step) + 1 }, (_, i) => +(i * step).toFixed(10));
}
