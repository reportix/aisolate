"use client";
import { type ReactNode, useEffect, useRef, useState } from "react";
import type { AirlockListing } from "@/lib/api";
import { cx } from "@/ui";

export type PickerState = "loading" | "failed" | "none" | "ready";

type Props = {
  /** The airlocks to choose from, in AIsolate's order: every one the user may ask for a new chat, an
   * open chat's own airlock only, since a chat stays with its airlock. */
  options: AirlockListing[];
  selected: string;
  state: PickerState;
  onPick: (airlockId: string) => void;
};

/** The pill at the foot of the composer: which airlock answers, and the list to pick another from. */
export function AirlockPicker({ options, selected, state, onPick }: Props) {
  if (state === "loading") return <Pill muted>Loading airlocks…</Pill>;
  if (state === "failed") return <Pill muted>Could not load your airlocks</Pill>;
  if (state === "none") return <Pill muted>No airlock available</Pill>;
  const current = options.find((o) => o.id === selected);
  const label = current?.name ?? "Choose an airlock";
  // Always a list, one airlock or ten: the pill reads as the place to choose.
  return (
    <Menu label={label} name={current ? `Airlock ${label}` : label}>
      {options.map((o) => (
        <MenuRow
          key={o.id}
          label={o.name}
          hint={o.description}
          selected={o === current}
          onClick={() => onPick(o.id)}
        />
      ))}
    </Menu>
  );
}

const PILL = "flex max-w-[16rem] items-center gap-1.5 rounded-lg px-2 py-1 text-xs";

function Pill({ muted = false, children }: { muted?: boolean; children: ReactNode }) {
  return (
    <span className={cx(PILL, muted ? "text-ink-tertiary" : "font-medium text-ink-secondary")}>
      <AirlockIcon />
      <span className="truncate">{children}</span>
    </span>
  );
}

function Menu({ label, name, children }: { label: string; name: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  // Picking or Escape hands focus back to the button the list opened from.
  const close = () => {
    setOpen(false);
    trigger.current?.focus();
  };

  useEffect(() => {
    if (!open) {
      return;
    }
    const closeOnOutside = (event: MouseEvent) => {
      if (!box.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        trigger.current?.focus();
      }
    };
    document.addEventListener("mousedown", closeOnOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("mousedown", closeOnOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  return (
    <div className="relative" ref={box}>
      <button
        ref={trigger}
        type="button"
        aria-label={name}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className={cx(
          PILL,
          "font-medium text-ink-secondary transition-colors hover:bg-desk hover:text-ink",
        )}
      >
        <AirlockIcon />
        <span className="truncate">{label}</span>
        <ChevronDown />
      </button>
      {open && (
        // A pick bubbles up here and closes the list. The list opens upward: the pill sits at the
        // foot of the page.
        <div
          onClick={close}
          className="absolute bottom-full left-0 z-20 mb-1.5 w-72 overflow-hidden rounded-2xl border border-hairline bg-paper p-1 shadow-[var(--shadow-overlay)]"
        >
          {children}
        </div>
      )}
    </div>
  );
}

function MenuRow({
  label,
  hint,
  selected = false,
  onClick,
}: {
  label: string;
  hint?: string;
  selected?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full items-start gap-2 rounded-xl px-3 py-2 text-left hover:bg-desk"
    >
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm text-ink">{label}</span>
        {hint && <span className="block truncate text-xs text-ink-tertiary">{hint}</span>}
      </span>
      {selected && (
        <span aria-hidden="true" className="pt-0.5 text-pen">
          ✓
        </span>
      )}
      {selected && <span className="sr-only">selected</span>}
    </button>
  );
}

/** The airlock glyph the AIsolate UI uses (libs/ui icons.tsx): a shield with a check. */
function AirlockIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      className="h-3.5 w-3.5 shrink-0 text-purple"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />
      <path d="m9 12 2 2 4-4" />
    </svg>
  );
}

function ChevronDown() {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      className="h-3 w-3 shrink-0 text-ink-tertiary"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M6 9l6 6 6-6" />
    </svg>
  );
}
