import { type Bar, short } from "@/lib/metrics";

/**
 * Counts over the period as horizontal bars, one hue, the largest first: what each counts on the
 * left, the count at the bar's tip. Every value is written, so no hover is needed to read one.
 */
export function BarChart({
  bars,
  empty,
  label,
}: {
  bars: Bar[];
  empty: string;
  /** Words for what a bar counts (a refusal's reason); without it the name is a machine string. */
  label?: (name: string) => string;
}) {
  if (bars.length === 0) return <p className="px-4 py-3 text-sm text-ink-tertiary">{empty}</p>;
  const max = Math.max(...bars.map((b) => b.count), 1);
  return (
    <ul className="grid gap-2.5 px-4 py-3.5">
      {bars.map((b) => (
        <li
          key={`${b.space}/${b.airlock}/${b.name}`}
          className="grid grid-cols-[minmax(0,14rem)_1fr] items-center gap-3 text-sm"
          title={`${b.space} / ${b.airlock}: ${label ? label(b.name) : b.name}, ${b.count}`}
        >
          <span className="min-w-0 truncate">
            {label ? label(b.name) : b.name}
            <span className="ml-1.5 text-xs text-ink-tertiary">{`${b.space} / ${b.airlock}`}</span>
          </span>
          <span className="flex items-center gap-2">
            <span
              className="h-3 rounded-r-[4px] bg-[var(--series-1)]"
              style={{ width: `${(b.count / max) * 85}%`, minWidth: 2 }}
            />
            <span className="text-xs font-medium text-ink">{short(b.count)}</span>
          </span>
        </li>
      ))}
    </ul>
  );
}
