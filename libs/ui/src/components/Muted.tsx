import type { HTMLAttributes } from "react";
import { cx } from "../cx";

/** Secondary text: loading and empty states, hints. */
export function Muted({ className, ...rest }: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cx("text-sm text-ink-tertiary", className)} {...rest} />;
}
