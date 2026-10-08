import type { ReactNode } from "react";
import { TableSkeleton } from "./Skeleton";
import { tableFrame } from "./Table";

type Props = {
  /** A plain subheading over the frame; none where the page's title already names the list. */
  title?: ReactNode;
  /** One line under the subheading: who or what the rows are. */
  description?: ReactNode;
  /** Right-aligned over the frame, on the subheading's line: the section's action. */
  action?: ReactNode;
  /** While its data loads: a skeleton of this shape in place of the children. */
  loading?: false | { rows: number; cols: number };
  children?: ReactNode;
};

/**
 * Every list and definition list as GitLab's CRUD lists sit: the subheading and the action above,
 * outside any border, then a hairline frame holding only the table or the rows; while loading, the
 * skeleton in the same frame. An empty table keeps its heads over one quiet row (`EmptyRow`).
 */
export function Section({ title, description, action, loading, children }: Props) {
  return (
    <section className="grid min-w-0 gap-3">
      {(title || action) && (
        <header>
          {/* 32px with or without its button, so every subheading is one height. */}
          <div className="flex min-h-8 flex-wrap items-center justify-between gap-x-4 gap-y-2">
            {title && <h2 className="text-base font-semibold tracking-snug">{title}</h2>}
            {action && <div className="ml-auto">{action}</div>}
          </div>
          {description && <p className="max-w-2xl text-sm text-ink-secondary">{description}</p>}
        </header>
      )}
      <div className={`min-w-0 ${tableFrame}`}>
        {loading ? <TableSkeleton {...loading} /> : children}
      </div>
    </section>
  );
}
