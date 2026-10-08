"use client";

import { Button, ChevronDownIcon } from "@aisolate/ui";
import { type KeyboardEvent, useEffect, useId, useRef, useState } from "react";
import type { ExportFormat } from "@/lib/audit";

const FORMATS: [ExportFormat, string][] = [
  ["pdf", "PDF"],
  ["json", "JSON"],
  ["xbrl", "XBRL"],
];

const ITEM = '[role="menuitem"]';

type Props = { onExport: (format: ExportFormat) => void; busy: boolean };

/**
 * "Export as" and its menu of the three formats, in the user menu's panel. Opening it focuses the
 * first; the arrows move, Escape closes it back onto the button, and a click elsewhere or leaving
 * it closes it.
 */
export function ExportMenu({ onExport, busy }: Props) {
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;
    wrap.current?.querySelector<HTMLElement>(ITEM)?.focus();
    const away = (e: PointerEvent) => {
      if (!wrap.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", away);
    return () => document.removeEventListener("pointerdown", away);
  }, [open]);

  const close = () => {
    setOpen(false);
    button.current?.focus();
  };

  const keys = (e: KeyboardEvent) => {
    if (e.key === "Escape") {
      e.preventDefault();
      close();
    } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const items = [...wrap.current!.querySelectorAll<HTMLElement>(ITEM)];
      const at = items.indexOf(document.activeElement as HTMLElement);
      items[(at + (e.key === "ArrowDown" ? 1 : items.length - 1)) % items.length].focus();
    }
  };

  return (
    <div
      ref={wrap}
      className="relative"
      onKeyDown={open ? keys : undefined}
      onBlur={(e) => {
        if (!wrap.current?.contains(e.relatedTarget as Node)) setOpen(false);
      }}
    >
      {/* Not disabled while busy: a disabled button would drop the focus the menu gave back. */}
      <Button
        ref={button}
        variant="secondary"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-disabled={busy}
        onClick={() => !busy && setOpen(!open)}
      >
        {busy ? "Exporting…" : "Export as"}
        <ChevronDownIcon size={14} />
      </Button>
      {open && (
        <div
          id={menuId}
          role="menu"
          aria-label="Export as"
          className="absolute top-full right-0 z-50 mt-1 w-32 rounded-lg border border-hairline bg-paper p-1 shadow-overlay"
        >
          {FORMATS.map(([format, label]) => (
            <button
              key={format}
              type="button"
              role="menuitem"
              onClick={() => {
                close();
                onExport(format);
              }}
              className="flex w-full rounded px-3 py-2 text-sm text-ink-secondary transition-colors duration-150 hover:bg-desk hover:text-ink"
            >
              {label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
