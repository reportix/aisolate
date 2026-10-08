export const PERIODS = {
  "10m": { label: "Last 10 minutes", ms: 600_000 },
  "1h": { label: "Last hour", ms: 3_600_000 },
  "24h": { label: "Last 24 hours", ms: 86_400_000 },
  "7d": { label: "Last 7 days", ms: 7 * 86_400_000 },
  "30d": { label: "Last 30 days", ms: 30 * 86_400_000 },
  all: { label: "All time", ms: null },
} as const;

export type Period = keyof typeof PERIODS;

/**
 * The period an address names, or `fallback` when it names none of ours. An own-property check:
 * `in` also answers for "constructor" and "toString", which have no span to count back.
 */
export const periodOf = (raw: string, fallback: Period): Period =>
  Object.hasOwn(PERIODS, raw) ? (raw as Period) : fallback;
