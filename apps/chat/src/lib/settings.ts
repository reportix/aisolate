import type { AirlockListing } from "./api";
import { env } from "./env";

/** Where the chat talks to: AIsolate's URL and the deployed airlock. */
export type Settings = {
  aisolateUrl: string;
  airlockId: string;
};

const KEY = "aisolate.settings";

export function defaultSettings(): Settings {
  return {
    aisolateUrl: env.aisolateUrl,
    airlockId: env.airlockId,
  };
}

export function loadSettings(storage: Storage | undefined = safeStorage()): Settings {
  const defaults = defaultSettings();
  try {
    const raw = storage?.getItem(KEY);
    if (!raw) return defaults;
    const parsed = JSON.parse(raw) as Partial<Settings>;
    // Field by field: a key an older image stored is dropped, never kept.
    return {
      // Always the image's: Settings has no field for it, so a stored one would stick for good.
      aisolateUrl: defaults.aisolateUrl,
      airlockId: typeof parsed.airlockId === "string" ? parsed.airlockId : defaults.airlockId,
    };
  } catch {
    return defaults;
  }
}

export function saveSettings(
  settings: Settings,
  storage: Storage | undefined = safeStorage(),
): void {
  try {
    storage?.setItem(KEY, JSON.stringify(settings));
  } catch {
    // Private mode or blocked storage: the settings live for this page only.
  }
}

function safeStorage(): Storage | undefined {
  try {
    return typeof window === "undefined" ? undefined : window.localStorage;
  } catch {
    return undefined;
  }
}

// A tiny external store so components read settings with useSyncExternalStore: no setState in
// effects, and hydration uses the environment defaults before the browser value takes over.
let snapshot: Settings | null = null;
const serverSnapshot = defaultSettings();
const listeners = new Set<() => void>();

export const settingsStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => void listeners.delete(listener);
  },
  getSnapshot(): Settings {
    return (snapshot ??= loadSettings());
  },
  getServerSnapshot(): Settings {
    return serverSnapshot;
  },
  set(next: Settings) {
    saveSettings(next);
    snapshot = next;
    listeners.forEach((l) => l());
  },
};

/**
 * The airlock the chat should talk to: the configured one if AIsolate still admits it, else the only
 * one there is, else nothing (the user must choose).
 */
export function pickAirlock(configured: string, available: AirlockListing[]): string {
  if (available.some((a) => a.id === configured)) return configured;
  return available.length === 1 ? available[0].id : "";
}
