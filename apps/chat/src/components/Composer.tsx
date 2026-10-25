"use client";

import { type ReactNode, useRef, useState } from "react";

type Props = {
  streaming: boolean;
  /** No airlock to send to: the box stays visible, so the reason next to it is read in place. */
  disabled?: boolean;
  /** Which airlock answers, at the foot of the box. */
  picker: ReactNode;
  onSend: (text: string) => void;
  onStop: () => void;
};

export function Composer({ streaming, disabled = false, picker, onSend, onStop }: Props) {
  const [text, setText] = useState("");
  const ref = useRef<HTMLTextAreaElement>(null);
  const canSend = text.trim().length > 0 && !streaming && !disabled;

  const submit = () => {
    if (!canSend) return;
    onSend(text.trim());
    setText("");
    if (ref.current) ref.current.style.height = "auto";
  };

  return (
    <form
      // Focus is the border turning blue, nothing more: no halo, the card's shadow stays as it is.
      className="rounded-2xl border border-hairline-strong bg-paper px-3 pt-2.5 pb-2 shadow-[var(--shadow-card)] transition-colors focus-within:border-focus"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <textarea
        ref={ref}
        rows={1}
        value={text}
        placeholder="Ask a question…"
        aria-label="Message"
        disabled={disabled}
        className="no-focus-ring block max-h-48 min-h-9 w-full resize-none bg-transparent px-2 py-1.5 text-[0.95rem] leading-6 text-ink placeholder:text-ink-tertiary"
        onChange={(e) => {
          setText(e.target.value);
          e.target.style.height = "auto";
          e.target.style.height = `${e.target.scrollHeight}px`;
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            submit();
          }
        }}
      />
      <div className="flex items-center justify-between gap-3 pt-1">
        <div className="min-w-0">{picker}</div>
        <div className="flex shrink-0 items-center gap-2">
          {streaming ? (
            <IconButton label="Stop" tone="stop" onClick={onStop}>
              <rect x="7" y="7" width="10" height="10" rx="2" fill="currentColor" />
            </IconButton>
          ) : (
            <IconButton label="Send" tone="send" type="submit" disabled={!canSend}>
              <path
                d="M12 19V5M12 5l-6 6M12 5l6 6"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </IconButton>
          )}
        </div>
      </div>
    </form>
  );
}

function IconButton({
  label,
  tone,
  type = "button",
  disabled = false,
  onClick,
  children,
}: {
  label: string;
  tone: "send" | "stop";
  type?: "button" | "submit";
  disabled?: boolean;
  onClick?: () => void;
  children: ReactNode;
}) {
  const enabled = {
    send: "bg-sky text-ink-inverse shadow-[var(--shadow-soft)] hover:bg-sky-hover",
    stop: "bg-mark-wash text-mark hover:bg-mark-wash-strong",
  }[tone];
  return (
    <button
      type={type}
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full transition-colors ${
        disabled ? "cursor-not-allowed bg-panel text-ink-muted" : enabled
      }`}
    >
      <svg viewBox="0 0 24 24" className="h-4 w-4" aria-hidden="true">
        {children}
      </svg>
    </button>
  );
}
