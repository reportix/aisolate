"use client";

import { useSyncExternalStore } from "react";

/** A save the cluster did not take yet: stored, and the backend applies every space again. */
export const NOT_APPLIED =
  "Saved, not in effect yet: AIsolate tries again within five minutes. Until then the previous settings apply.";

// The page that save landed on: the notice shows there until the user moves on.
let shownOn: string | null = null;
const listeners = new Set<() => void>();

/** Where a save's notice shows: nowhere once the cluster took it, else the page the save lands on. */
export const notAppliedPath = (applied: boolean, next: string | void, pathname: string) =>
  applied ? null : next || pathname;

export function setNotAppliedOn(path: string | null): void {
  if (path === shownOn) return;
  shownOn = path;
  listeners.forEach((l) => l());
}

export function useNotAppliedOn(): string | null {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => shownOn,
    () => null,
  );
}
