"use client";

import { type ReactNode, useState } from "react";
import type { ChatRow } from "@/lib/chats";
import { cx } from "@/ui";

type Props = {
  /** null while AIsolate's airlock list is still loading, so no chat flashes as unavailable. */
  rows: ChatRow[] | null;
  activeChatId: string | null;
  /** The signed-in user's menu; `compact` on the collapsed rail. */
  account: (compact: boolean) => ReactNode;
  onNewChat: () => void;
  onSelectChat: (id: string) => void;
  onDeleteChat: (id: string) => void;
};

// Neutral: a plain row, gray on hover.
const NEW_CHAT = "text-ink transition-colors hover:bg-sidebar-hover active:bg-sidebar-active";
const NAV = "shrink-0 flex-col border-r border-sidebar-border bg-sidebar";

/** The saved chats, one list, newest first. Which airlock answers a chat is read in the composer. */
export function Sidebar({
  rows,
  activeChatId,
  account,
  onNewChat,
  onSelectChat,
  onDeleteChat,
}: Props) {
  const [collapsed, setCollapsed] = useState(false);

  if (collapsed) {
    return (
      <nav aria-label="Chats" className={cx("flex w-14 items-center gap-2 py-3", NAV)}>
        <button
          type="button"
          title="Expand"
          aria-label="Expand"
          onClick={() => setCollapsed(false)}
          className="rounded-lg px-2 py-1 text-xs text-ink-tertiary hover:bg-sidebar-hover hover:text-ink"
        >
          »
        </button>
        <button
          type="button"
          title="New chat"
          aria-label="New chat"
          onClick={onNewChat}
          className={cx("flex h-9 w-9 items-center justify-center rounded-lg", NEW_CHAT)}
        >
          <PlusIcon />
        </button>
        <div className="mt-auto">{account(true)}</div>
      </nav>
    );
  }

  return (
    <nav aria-label="Chats" className={cx("flex w-64", NAV)}>
      <div className="flex items-center justify-between px-4 pt-4 pb-3">
        <span className="text-xs font-medium text-ink-tertiary">Chats</span>
        <button
          type="button"
          title="Collapse"
          aria-label="Collapse"
          onClick={() => setCollapsed(true)}
          className="rounded-lg px-2 py-1 text-xs text-ink-tertiary hover:bg-sidebar-hover hover:text-ink"
        >
          «
        </button>
      </div>
      <div className="px-3 pb-3">
        <button
          type="button"
          onClick={onNewChat}
          className={cx(
            "flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm",
            NEW_CHAT,
          )}
        >
          <PlusIcon />
          New chat
        </button>
      </div>
      <ul className="min-h-0 flex-1 space-y-0.5 overflow-y-auto px-3 pb-3">
        {rows?.length === 0 && (
          <li className="px-2 py-3 text-xs text-ink-tertiary">No chats yet.</li>
        )}
        {rows?.map((c) => {
          const active = c.id === activeChatId;
          return (
            <li key={c.id} className="group relative">
              <button
                type="button"
                onClick={() => onSelectChat(c.id)}
                aria-current={active ? "true" : undefined}
                className={cx(
                  "block w-full truncate rounded-lg py-2 pr-8 pl-3 text-left text-sm transition-colors",
                  active
                    ? "bg-sidebar-active text-ink"
                    : c.available
                      ? "text-ink-secondary hover:bg-sidebar-hover hover:text-ink"
                      : "text-ink-tertiary hover:bg-sidebar-hover",
                )}
              >
                {c.title || "New chat"}
                {!c.available && <span className="font-normal"> · unavailable</span>}
              </button>
              <button
                type="button"
                aria-label={`Delete chat ${c.title || "New chat"}`}
                onClick={(e) => {
                  e.stopPropagation();
                  onDeleteChat(c.id);
                }}
                className="absolute top-1.5 right-1.5 hidden h-6 w-6 items-center justify-center rounded-lg text-ink-tertiary hover:bg-sidebar-border hover:text-ink group-focus-within:flex group-hover:flex pointer-coarse:flex"
              >
                ×
              </button>
            </li>
          );
        })}
      </ul>
      <div className="border-t border-sidebar-border p-3">{account(false)}</div>
    </nav>
  );
}

function PlusIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      className="h-4 w-4 shrink-0"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
    >
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}
