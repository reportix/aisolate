import type { ComponentProps } from "react";
import { cx } from "../cx";

type ButtonVariant = "primary" | "secondary";

// Both carry a 1px border, so a primary and a secondary side by side are the same height.
const VARIANT: Record<ButtonVariant, string> = {
  primary:
    "border-pen bg-pen text-ink-inverse hover:border-pen-hover hover:bg-pen-hover active:border-pen-press active:bg-pen-press",
  secondary: "border-hairline-strong bg-paper text-ink hover:bg-desk",
};

/** The button look for elements that are not buttons (a Link). One height, 32px, everywhere. */
export function buttonClass(variant: ButtonVariant = "primary", extra?: string) {
  return cx(
    "inline-flex h-8 shrink-0 items-center justify-center gap-1.5 rounded border px-3 text-sm font-medium whitespace-nowrap transition-colors duration-150 disabled:pointer-events-none disabled:opacity-50",
    VARIANT[variant],
    extra,
  );
}

type Props = ComponentProps<"button"> & { variant?: ButtonVariant };

/** The near-black pen only for the one primary action; `type` defaults to button so forms do not submit by accident. */
export function Button({ variant = "primary", className, type = "button", ...rest }: Props) {
  return <button type={type} className={buttonClass(variant, className)} {...rest} />;
}
