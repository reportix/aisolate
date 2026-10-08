"use client";

import { useSyncExternalStore } from "react";
import { env } from "./env";

export type Kind = "offline" | "server";
export type Connection =
  | { state: "up" }
  | { state: "down"; kind: Kind; attempt: number; nextAt: number }
  /** The probe answered again; the UI reloads itself. */
  | { state: "back" };

type Event =
  | { type: "down"; kind: Kind; now: number }
  | { type: "probeFailed"; now: number }
  | { type: "probeOk" }
  | { type: "retryNow"; now: number };

const BASE_MS = 2_000;
const CAP_MS = 30_000;

/** Exponential backoff with ±20% jitter, so reconnecting tabs do not knock in unison. */
export function backoffMs(attempt: number, rand: () => number = Math.random): number {
  return Math.round(Math.min(CAP_MS, BASE_MS * 2 ** attempt) * (0.8 + 0.4 * rand()));
}

const downAt = (kind: Kind, attempt: number, now: number, rand: () => number): Connection => ({
  state: "down",
  kind,
  attempt,
  nextAt: now + backoffMs(attempt, rand),
});

export function next(c: Connection, e: Event, rand: () => number = Math.random): Connection {
  switch (e.type) {
    case "down":
      // Every failing call reports; only the first one starts the clock.
      return c.state === "up" ? downAt(e.kind, 0, e.now, rand) : c;
    case "probeFailed":
      return c.state === "down" ? downAt(c.kind, c.attempt + 1, e.now, rand) : c;
    case "probeOk":
      return c.state === "down" ? { state: "back" } : c;
    case "retryNow":
      return c.state === "down" ? { ...c, nextAt: e.now } : c;
  }
}

const UP: Connection = { state: "up" };
let current: Connection = UP;
const listeners = new Set<() => void>();
let timer: ReturnType<typeof setTimeout> | undefined;

function dispatch(e: Event): void {
  const c = next(current, e);
  if (c === current) return;
  current = c;
  listeners.forEach((l) => l());
  clearTimeout(timer);
  if (c.state === "down") timer = setTimeout(probe, Math.max(0, c.nextAt - Date.now()));
}

/** The one endpoint that answers without a token, so a probe never depends on the session. */
async function probe(): Promise<void> {
  try {
    const res = await fetch(`${env.aisolateUrl}/readyz`, { cache: "no-store" });
    dispatch(res.ok ? { type: "probeOk" } : { type: "probeFailed", now: Date.now() });
  } catch {
    dispatch({ type: "probeFailed", now: Date.now() });
  }
}

export const reportDown = (kind: Kind) => dispatch({ type: "down", kind, now: Date.now() });
export const retryNow = () => dispatch({ type: "retryNow", now: Date.now() });

export function useConnection(): Connection {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => current,
    () => UP,
  );
}
