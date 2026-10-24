"use client";
import { Button, cardClass } from "@/ui";
import type { User } from "oidc-client-ts";
import { useEffect, useRef, useState } from "react";
import { listAirlocks, type AirlockListing } from "@/lib/api";
import { connect, forgetRegistration } from "@/lib/aisolateAuth";
import type { Settings } from "@/lib/settings";

type Props = {
  open: boolean;
  onClose: () => void;
  settings: Settings;
  user: User | null;
};

/**
 * Whether this browser is connected to AIsolate, and the airlocks it admits, each with its
 * endpoint. Native <dialog>: focus, Escape and backdrop are free.
 */
export function SettingsDialog({ open, onClose, settings, user }: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  const signIn = () => {
    setError(null);
    rememberSettingsOpen();
    void connect(settings.aisolateUrl).catch(() => setError("Can't reach the sign-in service"));
  };

  return (
    <dialog
      ref={ref}
      aria-labelledby="settings-title"
      onClose={onClose}
      onClick={(e) => e.target === ref.current && onClose()}
      className="m-auto h-[min(32rem,100dvh)] w-[min(32rem,100vw)] border-0 bg-paper p-0 text-ink backdrop:bg-ink/25 sm:h-[min(32rem,calc(100dvh-2rem))] sm:w-[min(32rem,calc(100vw-2rem))] sm:rounded-2xl sm:border sm:border-hairline sm:shadow-[var(--shadow-overlay)]"
    >
      {open && (
        <div className="flex h-full flex-col">
          <header className="flex items-center justify-between border-b border-hairline px-5 py-3">
            <h2 id="settings-title" className="text-base font-semibold">
              Settings
            </h2>
            <button
              type="button"
              aria-label="Close"
              onClick={onClose}
              className="flex h-8 w-8 items-center justify-center rounded-lg text-ink-tertiary hover:bg-desk hover:text-ink"
            >
              ×
            </button>
          </header>
          <div className="min-h-0 flex-1 overflow-y-auto px-6 py-5">
            <div className="grid gap-6">
              {user && (
                <AirlockEndpoints token={user.access_token} aisolateUrl={settings.aisolateUrl} />
              )}
              {!user && (
                <section
                  className={cardClass("flex flex-wrap items-center justify-between gap-3 p-4")}
                >
                  <div>
                    <p className="text-sm font-medium">Not connected</p>
                    <p className="text-xs text-ink-tertiary">
                      {error ?? "You sign in on your identity provider's own page."}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      title="Register again if signing in keeps failing"
                      onClick={() => forgetRegistration(settings.aisolateUrl)}
                      className="rounded-lg px-2 py-1 text-xs text-ink-tertiary hover:text-ink"
                    >
                      Forget connection
                    </button>
                    <Button onClick={signIn}>Connect</Button>
                  </div>
                </section>
              )}
            </div>
          </div>
          <footer className="flex justify-end border-t border-hairline px-5 py-3">
            <Button onClick={onClose}>Done</Button>
          </footer>
        </div>
      )}
    </dialog>
  );
}

// Connect leaves the page for the identity provider. Settings being open is an external store, so
// the page can hydrate closed and then reopen it once the session flag says the user left from here.
const REOPEN_KEY = "aisolate.settingsOpen";
let open: boolean | null = null;
const openListeners = new Set<() => void>();

export const settingsDialog = {
  subscribe(listener: () => void) {
    openListeners.add(listener);
    return () => void openListeners.delete(listener);
  },
  getSnapshot: () => (open ??= settingsReopenRequested()),
  getServerSnapshot: () => false,
  set(next: boolean) {
    open = next;
    openListeners.forEach((l) => l());
  },
};

function rememberSettingsOpen() {
  try {
    window.sessionStorage.setItem(REOPEN_KEY, "yes");
  } catch {
    // no storage: the user opens Settings again by hand
  }
}

/** Whether Settings was open when the user left to sign in; asking clears it. */
function settingsReopenRequested(): boolean {
  try {
    const wanted = window.sessionStorage.getItem(REOPEN_KEY) !== null;
    window.sessionStorage.removeItem(REOPEN_KEY);
    return wanted;
  } catch {
    return false;
  }
}

/** The airlocks this user may ask, each with the URL a client posts its messages to. */
function AirlockEndpoints({ token, aisolateUrl }: { token: string; aisolateUrl: string }) {
  const [airlocks, setAirlocks] = useState<AirlockListing[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    listAirlocks(token, aisolateUrl)
      .then((found) => !cancelled && setAirlocks(found))
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [token, aisolateUrl]);

  const copy = (endpoint: string) => {
    void navigator.clipboard
      .writeText(endpoint)
      .then(() => {
        setCopied(endpoint);
        setTimeout(() => setCopied((shown) => (shown === endpoint ? null : shown)), 1500);
      })
      .catch(() => undefined); // copying refused: the endpoint stays on screen to select by hand
  };

  return (
    <section>
      <h3 className="pb-2 text-xs text-ink-secondary">Airlocks</h3>
      {failed && <p className="text-xs text-ink-tertiary">Could not load your airlocks.</p>}
      {!failed && airlocks === null && <p className="text-xs text-ink-tertiary">Loading…</p>}
      {airlocks?.length === 0 && (
        <p className="text-xs text-ink-tertiary">No airlock is deployed for you yet.</p>
      )}
      <ul className="grid gap-3">
        {airlocks?.map((airlock) => (
          <li key={airlock.id} className="rounded-2xl border border-hairline p-4">
            <p className="text-sm font-medium">{airlock.name}</p>
            <div className="mt-2 flex items-start gap-2">
              <code className="min-w-0 flex-1 break-all rounded-lg bg-desk px-2 py-1 font-mono text-xs text-ink">
                POST {airlock.endpoint}
              </code>
              <button
                type="button"
                onClick={() => copy(airlock.endpoint)}
                className="shrink-0 rounded-lg border border-hairline px-2 py-1 text-xs text-ink-secondary hover:bg-desk hover:text-ink"
              >
                {copied === airlock.endpoint ? "Copied" : "Copy"}
              </button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
