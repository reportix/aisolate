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
        ) : (
          <div key={m.id} className="flex max-w-[85%] flex-col items-end gap-1.5 self-end">
            <p className="whitespace-pre-wrap rounded-2xl rounded-br-md bg-sky-wash px-4 py-2.5 text-sm leading-6 text-ink">
              {m.content}
            </p>
          </div>
        ),
      )}
      <div ref={end} />
    </div>
  );
}
