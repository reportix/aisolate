import { Chip } from "@aisolate/ui";
import type { ReactNode } from "react";

/**
 * One label and value of a definition list in a Section's frame, 16px in from its sides; the label
 * column is 180px from sm. They share a baseline, so a chip sits level with its label.
 */
export function DefinitionRow({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <div className="grid items-baseline gap-1 px-4 py-3 sm:grid-cols-[180px_minmax(0,1fr)]">
      <dt className="text-xs text-ink-tertiary">{label}</dt>
      <dd className="min-w-0 text-sm">{children}</dd>
    </div>
  );
}

/** Machine names as chips, or the word for none. */
export function MonoChips({ names, none = "none" }: { names: string[]; none?: string }) {
  if (names.length === 0) return <span className="text-ink-tertiary">{none}</span>;
  return (
    <span className="flex flex-wrap gap-1">
      {names.map((n) => (
        <Chip key={n}>{n}</Chip>
      ))}
    </span>
  );
}

/** A machine string, and where it comes from when the one shown does not set it. */
export function Mono({
  value,
  none = "none",
  from,
}: {
  value: string;
  none?: string;
  from?: string;
}) {
  if (!value) return <span className="text-ink-tertiary">{none}</span>;
  return (
    <span className="flex flex-wrap items-baseline gap-x-2">
      <span className="wrap-anywhere">{value}</span>
      {from && <span className="text-xs text-ink-tertiary">{from}</span>}
    </span>
  );
}
