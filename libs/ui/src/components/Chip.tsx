import type { ReactNode } from "react";
import { cx } from "../cx";

/**
 * The chip look, for elements that are not spans (a Link): a small rounded tag in ink with a border
 * that shows (3.69:1 on paper).
 */
export function chipClass(extra?: string) {
  return cx(
    "inline-flex items-center gap-1 rounded-full border border-control bg-paper px-2 py-0.5 text-xs leading-4 font-normal text-ink",
    extra,
  );
}

export function Chip({ children }: { children: ReactNode }) {
  return <span className={chipClass()}>{children}</span>;
}
