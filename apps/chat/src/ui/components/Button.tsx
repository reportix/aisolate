import type { ButtonHTMLAttributes } from "react";
import { cx } from "../cx";

/** The one primary action, lavender with purple text; `type` defaults to button so forms do not submit by accident. */
export function Button({
  className,
  type = "button",
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      type={type}
      className={cx(
        "rounded-xl border border-mark-border bg-mark-wash px-4 py-2 text-sm font-medium text-mark transition-colors hover:bg-mark-wash-strong disabled:opacity-50",
        className,
      )}
      {...rest}
    />
  );
}
