"use client";

import { Field, fieldClass } from "@aisolate/ui";
import type { FormEvent } from "react";
import type { Period } from "@/lib/period";
import { useOverview } from "@/lib/useOverview";
import { PeriodSelect } from "./PeriodSelect";

type Filters = { space?: string; airlock?: string; period?: Period; q?: string };

/** The filters and the instant the period counts back from, set together. */
export type Scope<F> = { filters: F; now: number };

type Props<F extends Filters> = {
  filters: F;
  onApply: (next: F) => void;
  /** Tracing's search over the questions and its all-time period; Metrics has neither. */
  search?: boolean;
};

/**
 * One row above the list or the charts: the space and the airlock the caller sees, the period and,
 * on Tracing, a search of the questions. A select applies at once, the search on Enter.
 */
export function MonitoringFilters<F extends Filters>({ filters, onApply, search }: Props<F>) {
  const { data: overview } = useOverview();
  const spaces = overview?.spaces ?? [];
  const airlocks = [
    ...new Map(
      (overview?.airlocks ?? [])
        .filter((a) => !filters.space || a.space_id === filters.space)
        .map((a) => [a.id, a]),
    ).values(),
  ];
  const set = (patch: Partial<Filters>) => onApply({ ...filters, ...patch });
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    set({ q: String(new FormData(e.currentTarget).get("q") ?? "") });
  };

  return (
    <form onSubmit={submit} aria-label="Filters" className="flex flex-wrap items-end gap-3">
      {/* Wide enough for a space's full name on one line ("a long space name"). */}
      <div className="w-full sm:w-64">
        <Field id="f-space" label="Space">
          <select
            id="f-space"
            value={filters.space ?? ""}
            onChange={(e) => set({ space: e.target.value || undefined, airlock: undefined })}
            className={fieldClass}
          >
            <option value="">All spaces</option>
            {spaces.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <div className="w-full sm:w-64">
        <Field id="f-airlock" label="Airlock">
          <select
            id="f-airlock"
            value={filters.airlock ?? ""}
            onChange={(e) => set({ airlock: e.target.value || undefined })}
            className={fieldClass}
          >
            <option value="">All airlocks</option>
            {airlocks.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <PeriodSelect
        allTime={search}
        value={filters.period ?? "24h"}
        onChange={(period) => set({ period })}
      />
      {search && (
        <div className="w-full sm:min-w-72 sm:flex-1">
          <Field id="f-q" label="Question">
            <input
              id="f-q"
              name="q"
              type="search"
              defaultValue={filters.q}
              placeholder="Search the questions"
              className={fieldClass}
            />
          </Field>
        </div>
      )}
    </form>
  );
}
