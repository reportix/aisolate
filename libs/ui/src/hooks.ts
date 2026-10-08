"use client";

import { useCallback, useSyncExternalStore } from "react";

const listeners = new Set<() => void>();

function readStored(key: string, fallback: boolean): boolean {
  try {
    const stored = window.localStorage.getItem(key);
    return stored === null ? fallback : stored === "true";
  } catch {
    return fallback; // private mode or blocked storage
  }
}

/**
 * A boolean kept in localStorage, read as an external store: the server snapshot is the fallback,
 * so server and client agree at hydration and the stored value takes over right after.
 */
export function useStoredToggle(key: string, fallback: boolean): [boolean, () => void] {
  const subscribe = useCallback((notify: () => void) => {
    listeners.add(notify);
    window.addEventListener("storage", notify);
    return () => {
      listeners.delete(notify);
      window.removeEventListener("storage", notify);
    };
  }, []);
  const value = useSyncExternalStore(
    subscribe,
    () => readStored(key, fallback),
    () => fallback,
  );
  const toggle = useCallback(() => {
    try {
      window.localStorage.setItem(key, String(!readStored(key, fallback)));
    } catch {
      // Not persisted; nothing to toggle without storage.
    }
    listeners.forEach((notify) => notify());
  }, [key, fallback]);
  return [value, toggle];
}
