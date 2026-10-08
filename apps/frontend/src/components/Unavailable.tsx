"use client";

import { AIsolateLogo, AlertIcon, Button, CheckIcon } from "@aisolate/ui";
import { useEffect, useRef, useState } from "react";
import { logout } from "@/lib/auth";
import { retryNow, useConnection, type Kind } from "@/lib/connection";
import { env } from "@/lib/env";

const COPY: Record<Kind, { title: string; body: (host: string) => React.ReactNode }> = {
  // web.dev: say whose end the problem is on. "Failed to fetch" says neither.
  offline: {
    title: "You are offline",
    body: () => "Your device lost its network connection. AIsolate needs one for every screen.",
  },
  server: {
    title: "Can't reach AIsolate",
    body: (host) => (
      <>
        The control API at <span className="text-ink">{host}</span> is not answering. Nothing loads
        until it does.
      </>
    ),
  },
};

const secondsTo = (at: number, now: number) => Math.max(0, Math.ceil((at - now) / 1000));

/**
 * With the backend down every page is empty, so nothing behind this is worth offering. It covers
 * the shell and reloads the moment the probe answers again, so nobody has to click.
 */
export function Unavailable() {
  const connection = useConnection();
  const nextAt = connection.state === "down" ? connection.nextAt : null;
  const [now, setNow] = useState(() => Date.now());
  const retry = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (nextAt === null) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [nextAt]);

  useEffect(() => {
    retry.current?.focus();
    if (connection.state === "back") window.location.reload();
  }, [connection.state]);

  if (connection.state === "up") return null;
  const back = connection.state === "back";
  const copy = connection.state === "down" ? COPY[connection.kind] : null;
  const left = nextAt === null ? 0 : secondsTo(nextAt, now);

  return (
    // The shell behind is inert while this shows (app/(app)/layout.tsx).
    <div role="alert" className="fixed inset-0 z-50 flex items-center justify-center bg-desk p-4">
      <span className="absolute top-4 left-5 flex text-ink">
        <AIsolateLogo />
      </span>
      <div className="w-full max-w-md rounded-lg border border-hairline bg-paper px-8 py-10 text-center shadow-overlay">
        <span className={back ? "text-ok" : "text-error"}>
          {back ? <CheckIcon size={24} /> : <AlertIcon size={24} />}
        </span>
        <h1 className="mt-3 text-xl font-semibold tracking-tight">
          {back ? "AIsolate is answering again" : copy?.title}
        </h1>
        <p className="mt-2 text-sm text-ink-secondary">
          {back
            ? "Reloading to pick up where you left off; nothing you typed was sent."
            : copy?.body(new URL(env.aisolateUrl).host)}
        </p>
        {!back && (
          <div className="mt-6 flex items-center justify-center gap-2">
            <Button ref={retry} onClick={retryNow}>
              Try again
            </Button>
            <Button variant="secondary" onClick={() => void logout()}>
              Sign out
            </Button>
          </div>
        )}
        {!back && (
          // Off: inside the alert every tick would be read out; the title and body carry the state.
          <p className="mt-4 text-xs text-ink-tertiary tabular-nums" aria-live="off">
            {left === 0 ? "Checking…" : `Checking again in ${left}s`}
          </p>
        )}
      </div>
    </div>
  );
}
