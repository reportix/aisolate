import type { AirlockListing } from "./api";
import type { Message } from "./types";

/** A saved conversation: a resumable log of past exchanges, not shared conversational memory. */
export type Chat = {
  id: string;
  title: string;
  airlockId: string;
  messages: Message[];
  updatedAt: number;
};

// Each user's chats are stored under their own `sub`: a shared browser never opens one user's
// answers to the next.
let owner = "";
const chatsKey = () => `aisolate.chats.${owner}`;
const activeKey = () => `aisolate.activeChatId.${owner}`;

/** Points both stores at this user's chats; called on render, so no listener runs. */
export function chatsOwner(sub: string) {
  if (sub === owner) return;
  owner = sub;
  snapshot = null;
  activeId = undefined;
}

/** Drops this user's chats from the browser, on sign-out. */
export function clearChats() {
  try {
    window.localStorage.removeItem(chatsKey());
    window.localStorage.removeItem(activeKey());
  } catch {
    // No storage: nothing was kept.
  }
  snapshot = null;
  activeId = undefined;
}

function loadChats(): Record<string, Chat> {
  try {
    const raw = window.localStorage.getItem(chatsKey());
    return raw ? (JSON.parse(raw) as Record<string, Chat>) : {};
  } catch {
    return {};
  }
}

function saveChats(chats: Record<string, Chat>) {
  try {
    window.localStorage.setItem(chatsKey(), JSON.stringify(chats));
  } catch {
    // Private mode or blocked storage: history lives for this page only.
  }
}

// A tiny external store, the same shape as settingsStore: components read it with
// useSyncExternalStore, and every write goes through `set` so subscribers always agree.
let snapshot: Record<string, Chat> | null = null;
const listeners = new Set<() => void>();

export const chatsStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => void listeners.delete(listener);
  },
  getSnapshot(): Record<string, Chat> {
    return (snapshot ??= loadChats());
  },
  getServerSnapshot(): Record<string, Chat> {
    return {};
  },
  set(next: Record<string, Chat>) {
    saveChats(next);
    snapshot = next;
    listeners.forEach((l) => l());
  },
};

export function upsertChat(chat: Chat) {
  chatsStore.set({ ...chatsStore.getSnapshot(), [chat.id]: chat });
}

/** A chat's first save, on its first message: the airlock it is bound to is fixed from here on. */
export function startChat(chat: Pick<Chat, "id" | "title" | "airlockId">) {
  upsertChat({ ...chat, messages: [], updatedAt: Date.now() });
}

/**
 * The chat a message goes to: the one open now, else a new one started for it. Read when the message
 * is sent, never from an older render, so retrying a failed first message stays in its chat.
 */
export function chatFor(text: string, airlockId: string): string {
  const open = activeChatStore.getSnapshot();
  if (open && chatsStore.getSnapshot()[open]) return open;
  const id = crypto.randomUUID();
  startChat({ id, title: titleFrom(text), airlockId });
  activeChatStore.set(id);
  return id;
}

export function deleteChat(id: string) {
  const next = { ...chatsStore.getSnapshot() };
  delete next[id];
  chatsStore.set(next);
}

/**
 * A saved chat's messages, to open. Nothing streams into a chat as it opens, so an answer left
 * streaming (a reload, a switch or a sign-out mid-answer) is shown as stopped.
 */
export function messagesOf(chat: Chat | undefined): Message[] {
  const messages = chat?.messages ?? [];
  return messages.some((m) => m.status === "streaming")
    ? messages.map((m) => (m.status === "streaming" ? { ...m, status: "stopped" } : m))
    : messages;
}

/** Saved chats, newest first, for the sidebar list. */
export function sortedChats(chats: Record<string, Chat>): Chat[] {
  return Object.values(chats).sort((a, b) => b.updatedAt - a.updatedAt);
}

/** A saved chat as the sidebar lists it, and whether its airlock is still one AIsolate admits. */
export type ChatRow = Chat & { available: boolean };

/** Every saved chat, newest first, whatever its airlock; one whose airlock is gone is marked so. */
export function listChats(chats: Record<string, Chat>, listing: AirlockListing[]): ChatRow[] {
  return sortedChats(chats).map((c) => ({
    ...c,
    available: listing.some((a) => a.id === c.airlockId),
  }));
}

/** The first user message, trimmed to a short title. */
export function titleFrom(message: string): string {
  const trimmed = message.trim().replace(/\s+/g, " ");
  return trimmed.length > 60 ? `${trimmed.slice(0, 60)}…` : trimmed;
}

// Which saved chat (if any) is on screen, so a refresh resumes it instead of starting fresh.
let activeId: string | null | undefined;
const activeListeners = new Set<() => void>();

export const activeChatStore = {
  subscribe(listener: () => void) {
    activeListeners.add(listener);
    return () => void activeListeners.delete(listener);
  },
  getSnapshot(): string | null {
    if (activeId === undefined) {
      try {
        activeId = window.localStorage.getItem(activeKey());
      } catch {
        activeId = null;
      }
    }
    return activeId;
  },
  getServerSnapshot(): string | null {
    return null;
  },
  set(id: string | null) {
    activeId = id;
    try {
      if (id) window.localStorage.setItem(activeKey(), id);
      else window.localStorage.removeItem(activeKey());
    } catch {
      // No storage: the chat still works, it just won't resume after a refresh.
    }
    activeListeners.forEach((l) => l());
  },
};
