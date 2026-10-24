import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Chat } from "../chats";
import type { Message } from "../types";

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (k) => map.get(k) ?? null,
    key: (i) => [...map.keys()][i] ?? null,
    removeItem: (k) => void map.delete(k),
    setItem: (k, v) => void map.set(k, v),
  };
}

async function freshModule(localStorage = memoryStorage()) {
  vi.resetModules();
  vi.stubGlobal("window", { localStorage });
  return import("../chats");
}

const chat = (id: string, updatedAt: number): Chat => ({
  id,
  title: id,
  airlockId: "acme-hr",
  messages: [],
  updatedAt,
});

describe("titleFrom", () => {
  it("givenShortMessage_whenTitling_thenKeepsItWhole", async () => {
    // Arrange
    const { titleFrom } = await freshModule();
    // Act / Assert
    expect(titleFrom("  What is my salary?  ")).toBe("What is my salary?");
  });

  it("givenLongMessage_whenTitling_thenTruncatesWithEllipsis", async () => {
    // Arrange
    const { titleFrom } = await freshModule();
    const long = "a".repeat(80);
    // Act
    const title = titleFrom(long);
    // Assert
    expect(title).toBe(`${"a".repeat(60)}…`);
  });

  it("givenInternalNewlinesAndSpaces_whenTitling_thenCollapsesToSingleSpaces", async () => {
    // Arrange
    const { titleFrom } = await freshModule();
    // Act / Assert
    expect(titleFrom("What is\n\nmy   salary?")).toBe("What is my salary?");
  });
});

describe("sortedChats", () => {
  it("givenChatsOutOfOrder_whenSorting_thenNewestUpdatedFirst", async () => {
    // Arrange
    const { sortedChats } = await freshModule();
    const chats = { a: chat("a", 1), b: chat("b", 3), c: chat("c", 2) };
    // Act
    const sorted = sortedChats(chats);
    // Assert
    expect(sorted.map((c) => c.id)).toEqual(["b", "c", "a"]);
  });
});

describe("listChats", () => {
  const listing = [
    { id: "hr", name: "HR", description: "", endpoint: "", tools: [] },
    { id: "finance", name: "Finance", description: "", endpoint: "", tools: [] },
  ];
  const on = (airlockId: string, id: string, updatedAt: number): Chat => ({
    ...chat(id, updatedAt),
    airlockId,
  });

  it("givenChatsOfSeveralAirlocks_whenListing_thenOneListNewestFirstWhateverTheAirlock", async () => {
    // Arrange
    const { listChats } = await freshModule();
    const chats = {
      a: on("finance", "a", 1),
      b: on("gone-old", "b", 2),
      c: on("gone-new", "c", 5),
      d: on("hr", "d", 3),
    };
    // Act
    const rows = listChats(chats, listing);
    // Assert
    expect(rows.map((r) => r.id)).toEqual(["c", "d", "b", "a"]);
  });

  it("givenAChatOfAnAirlockNoLongerListed_whenListing_thenItIsMarkedUnavailable", async () => {
    // Arrange
    const { listChats } = await freshModule();
    // Act
    const rows = listChats({ a: on("hr", "a", 1), b: on("gone", "b", 2) }, listing);
    // Assert
    expect(rows.map((r) => [r.id, r.available])).toEqual([
      ["b", false],
      ["a", true],
    ]);
  });
});

describe("chatsStore", () => {
  it("givenChatUpserted_whenReadBack_thenItRoundTripsThroughStorage", async () => {
    // Arrange
    const { chatsStore, upsertChat } = await freshModule();
    const c = chat("a", 1);
    // Act
    upsertChat(c);
    // Assert
    expect(chatsStore.getSnapshot()).toEqual({ a: c });
  });

  it("givenChatDeleted_whenReadBack_thenItIsGone", async () => {
    // Arrange
    const { chatsStore, upsertChat, deleteChat } = await freshModule();
    upsertChat(chat("a", 1));
    upsertChat(chat("b", 2));
    // Act
    deleteChat("a");
    // Assert
    expect(Object.keys(chatsStore.getSnapshot())).toEqual(["b"]);
  });

  it("givenSubscriber_whenChatUpserted_thenListenerFires", async () => {
    // Arrange
    const { chatsStore, upsertChat } = await freshModule();
    const listener = vi.fn();
    chatsStore.subscribe(listener);
    // Act
    upsertChat(chat("a", 1));
    // Assert
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe("activeChatStore", () => {
  beforeEach(() => vi.unstubAllGlobals());

  it("givenIdSet_whenReadBack_thenItRoundTripsThroughStorage", async () => {
    // Arrange
    const { activeChatStore } = await freshModule();
    // Act
    activeChatStore.set("a");
    // Assert
    expect(activeChatStore.getSnapshot()).toBe("a");
  });

  it("givenIdSetThenCleared_whenReadBack_thenItIsNull", async () => {
    // Arrange
    const { activeChatStore } = await freshModule();
    activeChatStore.set("a");
    // Act
    activeChatStore.set(null);
    // Assert
    expect(activeChatStore.getSnapshot()).toBeNull();
  });

  it("givenNothingStored_whenReadBack_thenItIsNull", async () => {
    // Arrange
    const { activeChatStore } = await freshModule();
    // Act / Assert
    expect(activeChatStore.getSnapshot()).toBeNull();
  });
});

describe("chatsOwner", () => {
  it("givenElenasChats_whenMarkSignsInOnTheSameBrowser_thenHeSeesNoneOfThem", async () => {
    // Arrange
    const { activeChatStore, chatsOwner, chatsStore, upsertChat } = await freshModule();
    chatsOwner("elena");
    upsertChat(chat("salary", 1));
    activeChatStore.set("salary");
    // Act
    chatsOwner("mark");
    // Assert
    expect(chatsStore.getSnapshot()).toEqual({});
    expect(activeChatStore.getSnapshot()).toBeNull();
  });

  it("givenElenasChats_whenCleared_thenTheyAreGoneFromTheBrowser", async () => {
    // Arrange
    const storage = memoryStorage();
    const { activeChatStore, chatsOwner, chatsStore, clearChats, upsertChat } =
      await freshModule(storage);
    chatsOwner("elena");
    upsertChat(chat("salary", 1));
    activeChatStore.set("salary");
    // Act
    clearChats();
    // Assert
    expect(storage.length).toBe(0);
    expect(chatsStore.getSnapshot()).toEqual({});
    expect(activeChatStore.getSnapshot()).toBeNull();
  });
});

describe("chatFor", () => {
  const hr = "acme-hr";

  it("givenNoOpenChat_whenAFailedFirstMessageIsRetried_thenBothLandInOneChat", async () => {
    // Arrange
    const { activeChatStore, chatFor, chatsStore } = await freshModule();
    const first = chatFor("What is my salary?", hr);
    // Act
    const retried = chatFor("What is my salary?", hr);
    // Assert
    expect(retried).toBe(first);
    expect(Object.keys(chatsStore.getSnapshot())).toEqual([first]);
    expect(activeChatStore.getSnapshot()).toBe(first);
  });

  it("givenAnOpenChat_whenAMessageIsSent_thenItGoesToThatChat", async () => {
    // Arrange
    const { activeChatStore, chatFor, chatsStore, upsertChat } = await freshModule();
    upsertChat(chat("salary", 1));
    activeChatStore.set("salary");
    // Act
    const id = chatFor("And my bonus?", hr);
    // Assert
    expect(id).toBe("salary");
    expect(Object.keys(chatsStore.getSnapshot())).toEqual(["salary"]);
  });
});

describe("messagesOf", () => {
  it("givenAnAnswerLeftStreaming_whenTheChatOpens_thenItShowsAsStopped", async () => {
    // Arrange
    const { messagesOf } = await freshModule();
    const message = (id: string, status: Message["status"]): Message => ({
      id,
      role: "assistant",
      content: "",
      toolCalls: [],
      evidence: [],
      status,
    });
    const saved = {
      ...chat("a", 1),
      messages: [message("m1", "done"), message("m2", "streaming")],
    };
    // Act
    const opened = messagesOf(saved);
    // Assert
    expect(opened.map((m) => m.status)).toEqual(["done", "stopped"]);
  });
});
