"use client";

import { useEffect, useState, type ReactNode } from "react";
import { AlertIcon, InfoIcon } from "./icons";

/** Inputs, selects and textareas share one look; a control marked `aria-invalid` turns red. */
export const fieldClass =
  "w-full rounded border border-control bg-paper px-3 py-2 text-sm placeholder:text-ink-tertiary aria-[invalid=true]:border-error";

type Props = {
  /**
   * The control's id: the label points at it; the control is described by `${id}-error ${id}-help`
   * (an id with nothing behind it is skipped).
   */
  id: string;
  label: string;
  /** Only where the label alone is not clear: behind an info button after it, in a tooltip. */
  help?: ReactNode;
  /** Appends "(optional)"; required fields carry no marker. */
  optional?: boolean;
  errors?: string[];
  children: ReactNode;
};

/** A label above its control, its help behind an info button, the errors under the control. */
export function Field({ id, label, help, optional, errors = [], children }: Props) {
  return (
    <div className="grid gap-1">
      <div className="flex items-center gap-1">
        <label htmlFor={id} className="text-sm font-medium text-ink">
          {label}
          {optional && <span className="font-normal text-ink-tertiary"> (optional)</span>}
        </label>
        {help && (
          <Help id={`${id}-help`} label={label}>
            {help}
          </Help>
        )}
      </div>
      {children}
      {/* Always rendered so the control's aria-describedby resolves; takes no space while empty. */}
      <div id={`${id}-error`} className="empty:hidden">
        {errors.length > 0 && <FieldErrors items={errors} />}
      </div>
    </div>
  );
}

/**
 * The info button and its tooltip: hover, focus or a tap opens it, Escape, blur or leaving closes
 * it. The pointer can cross onto the tooltip (WCAG 1.4.13): its 6px gap is its own padding.
 */
function Help({ id, label, children }: { id: string; label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    const close = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [open]);
  return (
    <span
      className="relative inline-flex"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      {/* A 24px target round the 14px icon; the negative margin keeps the label's line 20px. */}
      <button
        type="button"
        aria-label={`About ${label}`}
        aria-describedby={id}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onClick={() => setOpen(true)}
        className="-my-0.5 inline-flex size-6 items-center justify-center rounded text-ink-tertiary transition-colors duration-150 hover:text-ink"
      >
        <InfoIcon size={14} />
      </button>
      {/* Hidden, it still describes the control: aria-describedby reads hidden text it names. */}
      <span id={id} role="tooltip" hidden={!open} className="absolute top-full -left-1 z-10 pt-1.5">
        <span className="block w-max max-w-md rounded-lg border border-hairline bg-paper px-3 py-2 text-xs text-ink shadow-overlay">
          {children}
        </span>
      </span>
    </span>
  );
}

/** Backend errors read "field: message"; shown under the field, the prefix is noise. */
function FieldErrors({ items }: { items: string[] }) {
  return (
    <ul className="grid gap-0.5 text-xs text-error">
      {items.map((e) => (
        <li key={e} className="flex gap-1">
          <AlertIcon size={12} className="mt-0.5 shrink-0" />
          {e.slice(e.indexOf(":") + 1).trim()}
        </li>
      ))}
    </ul>
  );
}
