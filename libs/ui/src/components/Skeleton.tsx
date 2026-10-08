import { cx } from "../cx";

/** A shimmering bar where content will land; sized by the caller. */
export function Skeleton({ className }: { className?: string }) {
  return (
    <span aria-hidden="true" className={cx("block animate-pulse rounded bg-panel", className)} />
  );
}

export function TableSkeleton({ rows = 3, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    // A status, so assistive technology announces it; a plain div's label is ignored.
    <div role="status" aria-busy="true" className="grid gap-3 px-4 py-4">
      <span className="sr-only">Loading</span>
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="grid gap-3" style={{ gridTemplateColumns: `repeat(${cols}, 1fr)` }}>
          {Array.from({ length: cols }, (_, c) => (
            <Skeleton key={c} className={c === 0 ? "h-4 w-3/4" : "h-4 w-1/2"} />
          ))}
        </div>
      ))}
    </div>
  );
}
