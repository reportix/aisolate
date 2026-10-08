import type { ReactNode } from "react";
import { cx } from "../cx";

/** Up to four facts, each its own paper card, spaced apart. */
export function Ledger({ children, className }: { children: ReactNode; className?: string }) {
  return <dl className={cx("grid gap-4 sm:grid-cols-2 lg:grid-cols-4", className)}>{children}</dl>;
}

/** One pastel per section, strongest behind the label and gone by the number. */
const TINT = {
  lavender: "from-tint-lavender",
  sand: "from-tint-sand",
  mint: "from-tint-mint",
  rose: "from-tint-rose",
};

type StatProps = {
  icon: ReactNode;
  label: string;
  value: ReactNode;
  tone: keyof typeof TINT;
};

export function Stat({ icon, label, value, tone }: StatProps) {
  return (
    <div
      className={cx(
        "overflow-hidden rounded-lg border border-hairline bg-paper bg-linear-to-b to-paper to-60%",
        TINT[tone],
      )}
    >
      <dt className="flex items-center gap-2.5 px-4 pt-3 text-sm text-ink">
        <span className="flex size-8 shrink-0 items-center justify-center rounded-full border border-hairline bg-paper text-ink-secondary">
          {icon}
        </span>
        {label}
      </dt>
      <dd className="px-4 pt-1.5 pb-3.5 text-2xl font-medium tracking-tighter tabular-nums">
        {value}
      </dd>
    </div>
  );
}
