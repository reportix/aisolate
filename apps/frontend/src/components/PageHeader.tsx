"use client";

import { Banner, Skeleton, TableSkeleton, Tile, cx } from "@aisolate/ui";
import type { ReactNode } from "react";
import { useTabTitle } from "@/lib/title";
import { Dates, type Dated, type Parent } from "./Dates";

type Props = {
  title: ReactNode;
  /** The entity's tile: its initial on a wash from its id. */
  tile?: { id: string; name: string };
  /** The entity's own description, or the one line of state a page must say. */
  description?: ReactNode;
  /** An entity page's created and updated times, shown under the description. */
  dates?: Dated;
  /** What the entity belongs to, outermost first, on the dates line. */
  parents?: Parent[];
  actions?: ReactNode;
};

/**
 * Every page's title but the dashboard's: the tile (an entity's), title and description, with the
 * page's actions flush right.
 */
export function PageHeader({ title, tile, description, dates, parents, actions }: Props) {
  // The tab says where the user is, as the heading does; a title still loading leaves "AIsolate".
  useTabTitle(typeof title === "string" ? title : undefined);
  return (
    <header className="flex flex-wrap items-start justify-between gap-4">
      {/* As GitLab's project header: the tile and the name on one row, the rest under both. */}
      <div className="min-w-0">
        <div className="flex min-w-0 items-center gap-3">
          {tile && <Tile id={tile.id} name={tile.name} />}
          <h1
            className={cx(
              "min-w-0 font-semibold tracking-tight",
              tile ? "text-[1.75rem] leading-tight" : "text-xl",
            )}
          >
            {title}
          </h1>
        </div>
        {description && (
          <p className={cx("max-w-2xl text-sm text-ink-secondary", tile ? "mt-3" : "mt-0.5")}>
            {description}
          </p>
        )}
        {dates && <Dates of={dates} parents={parents} />}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

type LoadingProps = {
  banner: string | null;
  /** The title bar's width class, about the name's. */
  width: string;
  rows: number;
  cols: number;
};

/** An entity page until it loads: its title as a bar, then the failure or a skeleton of its rows. */
export function EntityLoading({ banner, width, rows, cols }: LoadingProps) {
  return (
    <div className="grid gap-6">
      <PageHeader title={<Skeleton className={`h-6 ${width}`} />} />
      {banner ? <Banner message={banner} /> : <TableSkeleton rows={rows} cols={cols} />}
    </div>
  );
}
