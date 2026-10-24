"use client";

import { useEffect, useRef } from "react";
import type { Message } from "@/lib/types";
import { AssistantMessage } from "./AssistantMessage";

export function MessageList({ messages, userName }: { messages: Message[]; userName?: string }) {
  const end = useRef<HTMLDivElement>(null);
  // An answer is followed only while the reader is at the bottom, so scrolling up to reread holds.
  // Growing content fires no scroll event, so the last position the reader chose decides.
  const following = useRef(true);
  useEffect(() => {
    const scroller = end.current?.closest("main");
    if (!scroller) return;
    const onScroll = () => {
      following.current = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 48;
    };
    scroller.addEventListener("scroll", onScroll, { passive: true });
    return () => scroller.removeEventListener("scroll", onScroll);
  }, []);
  const last = messages.at(-1);
  // A new message (one sent, or another chat opened) always goes to the bottom.
  useEffect(() => {
    following.current = true;
  }, [messages.length]);
  useEffect(() => {
    if (following.current) end.current?.scrollIntoView({ block: "end" });
  }, [last?.content, last?.evidence.length, last?.toolCalls.length, messages.length]);

  return (
    <div className="flex flex-col gap-6">
      {messages.map((m) =>
        m.role !== "user" ? (
          <AssistantMessage key={m.id} message={m} userName={userName} />
        ) : m.attachment && !m.content ? (
          <AttachmentChip key={m.id} name={m.attachment} />
        ) : (
          <div key={m.id} className="flex max-w-[85%] flex-col items-end gap-1.5 self-end">
            <p className="whitespace-pre-wrap rounded-2xl rounded-br-md bg-sky-wash px-4 py-2.5 text-sm leading-6 text-ink">
              {m.content}
            </p>
            {m.attachment && <AttachmentChip name={m.attachment} small />}
          </div>
        ),
      )}
      <div ref={end} />
    </div>
  );
}

/** A document the user attached: a chip on their side of the thread (file icon + filename), small under a question. */
function AttachmentChip({ name, small = false }: { name: string; small?: boolean }) {
  return (
    <div
      className={
        small
          ? "flex max-w-full items-center gap-1.5 rounded-lg border border-hairline bg-paper px-2.5 py-1"
          : "mb-3 flex max-w-[85%] items-center gap-2.5 self-end rounded-2xl rounded-br-md border border-hairline-strong bg-paper px-3.5 py-2.5 shadow-[var(--shadow-soft)]"
      }
      title={name}
    >
      <svg
        viewBox="0 0 24 24"
        className={
          small ? "h-3.5 w-3.5 shrink-0 text-ink-tertiary" : "h-5 w-5 shrink-0 text-ink-tertiary"
        }
        fill="none"
        stroke="currentColor"
        strokeWidth={1.75}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M14 3v4a1 1 0 0 0 1 1h4" />
        <path d="M17 21H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z" />
      </svg>
      <span className={small ? "truncate text-xs text-ink-secondary" : "truncate text-sm text-ink"}>
        {name}
      </span>
    </div>
  );
}
