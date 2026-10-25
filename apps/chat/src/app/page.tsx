"use client";

import type { User } from "oidc-client-ts";
import { ApiError, Banner, UserMenu, Unauthorized, displayName } from "@/ui";
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { AuthGate } from "@/components/AuthGate";
import { Composer } from "@/components/Composer";
import { MessageList } from "@/components/MessageList";
import { Sidebar } from "@/components/Sidebar";
import { SettingsDialog, settingsDialog } from "@/components/SettingsDialog";
import { AirlockPicker, type PickerState } from "@/components/AirlockPicker";
import { listAirlocks, streamChat, type AirlockListing } from "@/lib/api";
import {
  activeChatStore,
  chatFor,
  chatsOwner,
  chatsStore,
  deleteChat,
  listChats,
  messagesOf,
  upsertChat,
} from "@/lib/chats";
import { reduce } from "@/lib/events";
import { NotConnected, accessToken, signOut, useAIsolateUser } from "@/lib/aisolateAuth";
import { pickAirlock, settingsStore, type Settings } from "@/lib/settings";
import type { Message } from "@/lib/types";

const LISTING_FAILED = "Could not load your airlocks";
const UNAVAILABLE = "This airlock is no longer available to you, so its chats stay closed.";
export default function Page() {
  const settings = useSyncExternalStore(
    settingsStore.subscribe,
    settingsStore.getSnapshot,
    settingsStore.getServerSnapshot,
  );
  const settingsOpen = useSyncExternalStore(
    settingsDialog.subscribe,
    settingsDialog.getSnapshot,
    settingsDialog.getServerSnapshot,
  );
  const user = useAIsolateUser(settings.aisolateUrl);

  return (
    <>
      <AuthGate user={user} aisolateUrl={settings.aisolateUrl}>
        {(signedIn) => <ChatShell key={signedIn.profile.sub} user={signedIn} settings={settings} />}
      </AuthGate>
      <SettingsDialog
        open={settingsOpen}
        onClose={() => settingsDialog.set(false)}
        settings={settings}
        user={user}
      />
    </>
  );
}

function ChatShell({ user, settings }: { user: User; settings: Settings }) {
  chatsOwner(user.profile.sub);
  const activeChatId = useSyncExternalStore(
    activeChatStore.subscribe,
    activeChatStore.getSnapshot,
    activeChatStore.getServerSnapshot,
  );
  const chats = useSyncExternalStore(
    chatsStore.subscribe,
    chatsStore.getSnapshot,
    chatsStore.getServerSnapshot,
  );
  const [messages, setMessages] = useState<Message[]>(() => {
    const id = activeChatStore.getSnapshot();
    return id ? messagesOf(chatsStore.getSnapshot()[id]) : [];
  });
  const [streaming, setStreaming] = useState(false);
  const [banner, setBanner] = useState<{ message: string; retry?: () => void } | null>(null);
  const [listing, setListing] = useState<AirlockListing[] | null>(null);
  const [reloads, setReloads] = useState(0);
  const abort = useRef<AbortController | null>(null);
  // Leaving the shell (a session lost mid-answer) stops the stream it can no longer save.
  useEffect(() => () => abort.current?.abort(), []);
  // The chat a stream started against, so a mid-stream chat switch can tell its own late updates
  // apart from stale ones meant for whatever is no longer on screen.
  const activeChatIdRef = useRef(activeChatId);
  useEffect(() => {
    activeChatIdRef.current = activeChatId;
  }, [activeChatId]);

  // Saves the open chat whenever its messages change — including mid-stream, so a reload never
  // loses more than the in-flight chunk. Opening a chat hands back the stored array itself, which
  // is skipped, so merely looking at a chat never moves it to the top.
  // Writes on every streamed chunk; throttle if that shows up as a perf problem.
  useEffect(() => {
    const existing = activeChatId ? chatsStore.getSnapshot()[activeChatId] : undefined;
    if (!existing || existing.messages === messages) return;
    upsertChat({ ...existing, messages, updatedAt: Date.now() });
  }, [activeChatId, messages]);

  // The airlocks AIsolate admits for this user: the sidebar's projects. The airlock pods serve
  // this list, so it fails while they are down; a load that succeeds again takes its banner away.
  useEffect(() => {
    let cancelled = false;
    listAirlocks(user.access_token, settings.aisolateUrl)
      .then((found) => {
        if (cancelled) return;
        setListing(found);
        setBanner((shown) => (shown?.message === LISTING_FAILED ? null : shown));
      })
      .catch(
        () =>
          !cancelled &&
          setBanner({
            message: LISTING_FAILED,
            retry: () => {
              setBanner(null);
              setReloads((n) => n + 1);
            },
          }),
      );
    return () => {
      cancelled = true;
    };
  }, [settings.aisolateUrl, user, reloads]);

  const rows = useMemo(() => listing && listChats(chats, listing), [chats, listing]);

  // Where a new chat goes. A stale or preset airlock is dropped; the only airlock there is is
  // selected without asking.
  useEffect(() => {
    if (!listing) return;
    const chosen = pickAirlock(settings.airlockId, listing);
    if (chosen !== settings.airlockId) settingsStore.set({ ...settings, airlockId: chosen });
  }, [listing, settings]);

  // Who answers: the open chat's own airlock, never another — else the airlock a new chat goes to.
  // An airlock that is gone resolves to nothing at all.
  const activeChat = activeChatId ? chats[activeChatId] : undefined;
  const airlockId = (activeChat ?? settings).airlockId;
  const current = listing?.find((a) => a.id === airlockId);
  const ready = Boolean(current);
  const unavailable = Boolean(listing && airlockId && !current);
  // What the pill offers: an open chat its own airlock, a new chat every airlock.
  const offered = activeChat ? (current ? [current] : []) : (listing ?? []);
  const pickerState: PickerState =
    banner?.message === LISTING_FAILED
      ? "failed"
      : !listing
        ? "loading"
        : offered.length === 0
          ? "none"
          : "ready";

  // A new chat goes where the pill points; an open chat keeps its airlock.
  const onPickAirlock = (id: string) => {
    if (!activeChat) settingsStore.set({ ...settings, airlockId: id });
  };

  const send = async (text: string) => {
    if (!current) return;
    setBanner(null);
    // A fresh chat is saved on its first message: nothing is kept for a chat
    // nobody used. Its airlock is fixed here, for good.
    const chatId = chatFor(text, airlockId);

    // Guards every update below: if the user switches to another chat mid-stream, this stream's
    // own late events must not go on writing into whatever is on screen now.
    const stillActive = () => activeChatIdRef.current === chatId;
    const patchLast = (fn: (m: Message) => Message) => {
      if (!stillActive()) return;
      setMessages((ms) => ms.map((m, i) => (i === ms.length - 1 ? fn(m) : m)));
    };

    const id = crypto.randomUUID();
    setMessages((ms) => [
      ...ms,
      { id: `u-${id}`, role: "user", content: text, toolCalls: [], evidence: [], status: "done" },
      {
        id: `a-${id}`,
        role: "assistant",
        content: "",
        toolCalls: [],
        evidence: [],
        status: "streaming",
      },
    ]);
    setStreaming(true);
    const controller = new AbortController();
    abort.current = controller;
    try {
      const token = await accessToken(settings.aisolateUrl);
      await streamChat(text, token, controller.signal, (e) => patchLast((m) => reduce(m, e)), {
        aisolateUrl: settings.aisolateUrl,
        airlockId,
      });
      // A stream that ends without a done frame still has to leave the message settled.
      patchLast((m) => (m.status === "streaming" ? { ...m, status: "done" } : m));
    } catch (e) {
      if (e instanceof NotConnected || e instanceof Unauthorized) {
        // No banner needed: signing out lands on the identity provider's login page.
        await signOut(settings.aisolateUrl);
        return;
      }
      if (e instanceof DOMException && e.name === "AbortError") {
        patchLast((m) => ({ ...m, status: "stopped" }));
        return;
      }
      const message =
        e instanceof ApiError && e.status === 403
          ? "You are not allowed to use this airlock."
          : e instanceof Error
            ? e.message
            : "Request failed";
      patchLast((m) => ({ ...m, status: "error", error: message }));
      if (stillActive()) {
        setBanner({
          message,
          retry: e instanceof ApiError && e.status === 403 ? undefined : () => void send(text),
        });
      }
    } finally {
      abort.current = null;
      setStreaming(false);
    }
  };

  const onNewChat = () => {
    abort.current?.abort();
    setBanner(null);
    activeChatStore.set(null);
    setMessages([]);
  };

  const onSelectChat = (id: string) => {
    if (id === activeChatId) return;
    abort.current?.abort();
    setBanner(null);
    activeChatStore.set(id);
    setMessages(messagesOf(chatsStore.getSnapshot()[id]));
  };

  const onDeleteChat = (id: string) => {
    deleteChat(id);
    if (id === activeChatId) onNewChat();
  };

  return (
    <div className="flex h-dvh">
      <Sidebar
        rows={rows}
        activeChatId={activeChatId}
        account={(compact) => (
          <UserMenu
            compact={compact}
            name={displayName(user)}
            email={user.profile.email}
            onOpenSettings={() => settingsDialog.set(true)}
            onSignOut={() => void signOut(settings.aisolateUrl)}
          />
        )}
        onNewChat={onNewChat}
        onSelectChat={onSelectChat}
        onDeleteChat={onDeleteChat}
      />
      <div className="flex min-w-0 flex-1 flex-col bg-paper">
        <main className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-6 py-8">
            {banner && (
              <Banner
                message={banner.message}
                actionLabel={banner.retry ? "Retry" : undefined}
                onAction={banner.retry}
              />
            )}
            {unavailable && !banner && <Banner message={UNAVAILABLE} />}
            {messages.length === 0 && listing && !banner && (
              <Home
                airlockName={current?.name}
                description={current?.description}
                none={!listing.length}
              />
            )}
            <MessageList messages={messages} userName={displayName(user)} />
          </div>
        </main>
        <footer>
          <div className="mx-auto w-full max-w-3xl px-6 pb-6 pt-2">
            <Composer
              streaming={streaming}
              disabled={!ready}
              picker={
                <AirlockPicker
                  options={offered}
                  selected={airlockId}
                  state={pickerState}
                  onPick={onPickAirlock}
                />
              }
              onSend={(t) => void send(t)}
              onStop={() => abort.current?.abort()}
            />
          </div>
        </footer>
      </div>
    </div>
  );
}

/** A chat before its first message: who answers it, in the airlock's own words. */
function Home({
  airlockName,
  description,
  none,
}: {
  airlockName?: string;
  description?: string;
  none: boolean;
}) {
  if (none) {
    return (
      <section className="grid gap-3 py-12">
        <h1 className="text-2xl font-semibold tracking-tight">No airlock yet</h1>
        <p className="text-sm text-ink-secondary">No airlock is deployed for you yet.</p>
      </section>
    );
  }
  if (!airlockName) {
    return (
      <section className="grid gap-3 py-12">
        <h1 className="text-2xl font-semibold tracking-tight">Choose an airlock</h1>
        <p className="text-sm text-ink-secondary">Pick one below, next to the message box.</p>
      </section>
    );
  }
  return (
    <section className="grid gap-3 py-12">
      <h1 className="text-[1.75rem] font-semibold tracking-tight">Ask {airlockName}</h1>
      {description && <p className="text-sm text-ink-secondary">{description}</p>}
    </section>
  );
}
