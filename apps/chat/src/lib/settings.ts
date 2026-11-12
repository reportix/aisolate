import type { AirlockListing, AirlockVersion } from "./api";
import { env } from "./env";

/**
 * Where the chat talks to: AIsolate's URL, the deployed airlock, and which of its versions by name.
 * `version` "" is unset: the airlock's newest, resolved once its versions are known.
 */
export type Settings = {
  aisolateUrl: string;
  airlockId: string;
  version: string;
};

const KEY = "aisolate.settings";

export function defaultSettings(): Settings {
  return {
    aisolateUrl: env.aisolateUrl,
    airlockId: env.airlockId,
    version: "",
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
      version: typeof parsed.version === "string" ? parsed.version : defaults.version,
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

/**
 * The (airlock, version) the chat should talk to: the airlock as `pickAirlock` says; of its versions
 * the configured one while the airlock still lists it, else the newest, else nothing.
 */
export function pickAirlockVersion(
  configured: Pick<Settings, "airlockId" | "version">,
  available: AirlockListing[],
): Pick<Settings, "airlockId" | "version"> {
  const airlockId = pickAirlock(configured.airlockId, available);
  const versions = available.find((a) => a.id === airlockId)?.versions ?? [];
  const kept =
    airlockId === configured.airlockId && versions.some((v) => v.version === configured.version);
  return { airlockId, version: kept ? configured.version : newest(versions) };
}

// Version names are slugs (a-z, 0-9, -), on which a numeric collation is libs/config's
// VERSION_ORDER: digit runs compare by value, so v10 is newer than v9, the rest character by character.
const byVersion = new Intl.Collator("en", { numeric: true }).compare;

function newest(versions: AirlockVersion[]): string {
  return (
    versions
      .map((v) => v.version)
      .sort(byVersion)
      .at(-1) ?? ""
  );
}
