"use client";

import { Field, fieldClass } from "@aisolate/ui";
import { PERIODS, type Period } from "@/lib/period";

type Props = {
  value: Period;
  /** A choice applies at once. */
  onChange: (period: Period) => void;
  /** Whether All time is offered: on Audit and Tracing, which search every event; not on Metrics. */
  allTime?: boolean;
};

/** The Period filter of a list or the charts: the spans of {@link PERIODS}, by their words. */
export function PeriodSelect({ value, onChange, allTime }: Props) {
  return (
    <div className="w-full sm:w-40">
      <Field id="f-period" label="Period">
        <select
          id="f-period"
          value={value}
          onChange={(e) => onChange(e.target.value as Period)}
          className={fieldClass}
        >
          {Object.entries(PERIODS)
            .filter(([v]) => allTime || v !== "all")
            .map(([v, p]) => (
              <option key={v} value={v}>
                {p.label}
              </option>
            ))}
        </select>
      </Field>
    </div>
  );
}
