import type { AirlockListing } from "@/lib/api";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loadSettings, pickAirlock, pickAirlockVersion, saveSettings } from "../settings";

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

describe("settings", () => {
  beforeEach(() => {
    vi.stubEnv("NEXT_PUBLIC_AISOLATE_URL", "http://localhost:8080");
    vi.stubEnv("NEXT_PUBLIC_AISOLATE_AIRLOCK_ID", "acme-hr");
  });
  afterEach(() => vi.unstubAllEnvs());

  it("givenNothingStored_whenLoaded_thenTheEnvironmentDefaults", () => {
    // Arrange
    const storage = memoryStorage();
    // Act
    const s = loadSettings(storage);
    // Assert
    expect(s).toEqual({ aisolateUrl: "http://localhost:8080", airlockId: "acme-hr", version: "" });
  });

  it("givenSavedSettings_whenLoaded_thenTheAirlockAndVersionRoundTripWithTheEnvironmentsUrl", () => {
    // Arrange
    const storage = memoryStorage();
    saveSettings(
      { aisolateUrl: "https://aisolate.example.com", airlockId: "hr-app-1", version: "pilot" },
      storage,
    );
    // Act
    const s = loadSettings(storage);
    // Assert
    expect(s).toEqual({
      aisolateUrl: "http://localhost:8080",
      airlockId: "hr-app-1",
      version: "pilot",
    });
  });

  it("givenSettingsStoredWithoutAVersion_whenLoaded_thenTheVersionIsUnset", () => {
    // Arrange
    const storage = memoryStorage();
    storage.setItem("aisolate.settings", JSON.stringify({ airlockId: "hr-app-1" }));
    // Act
    const s = loadSettings(storage);
    // Assert
    expect(s).toEqual({ aisolateUrl: "http://localhost:8080", airlockId: "hr-app-1", version: "" });
  });

  it("givenCorruptStoredValue_whenLoaded_thenIgnored", () => {
    // Arrange
    const storage = memoryStorage();
    storage.setItem("aisolate.settings", "{not json");
    // Act / Assert
    expect(loadSettings(storage).aisolateUrl).toBe("http://localhost:8080");
  });
});

describe("pickAirlock", () => {
  const airlock = (id: string): AirlockListing => ({ id, name: id, description: "", versions: [] });
  const listing = [airlock("acme-hr"), airlock("hr-app-1")];

  it.each([
    {
      state: "ConfiguredAirlockStillListed",
      configured: "hr-app-1",
      available: listing,
      picked: "hr-app-1",
    },
    {
      state: "ConfiguredAirlockGoneAndOneOther",
      configured: "founders-finance",
      available: [airlock("hr-app-1")],
      picked: "hr-app-1",
    },
    {
      state: "ConfiguredAirlockGoneAndSeveralOthers",
      configured: "founders-finance",
      available: listing,
      picked: "",
    },
    {
      state: "ConfiguredAirlockGoneAndNoOther",
      configured: "founders-finance",
      available: [],
      picked: "",
    },
    { state: "NoneConfiguredAndSeveralAvailable", configured: "", available: listing, picked: "" },
  ])("given$state_whenPicked_then$picked", ({ configured, available, picked }) => {
    // Arrange / Act
    const chosen = pickAirlock(configured, available);
    // Assert
    expect(chosen).toBe(picked);
  });
});

describe("pickAirlockVersion", () => {
  const airlock = (id: string, ...versions: string[]): AirlockListing => ({
    id,
    name: id,
    description: "",
    versions: versions.map((version) => ({ version, description: "", endpoint: "", tools: [] })),
  });
  const listing = [airlock("acme-hr", "v9", "v10", "v2"), airlock("hr-app-1", "pilot")];

  it.each([
    {
      state: "ConfiguredVersionStillListed",
      configured: { airlockId: "acme-hr", version: "v2" },
      available: listing,
      picked: { airlockId: "acme-hr", version: "v2" },
    },
    {
      state: "ConfiguredVersionPulled",
      configured: { airlockId: "acme-hr", version: "v3" },
      available: listing,
      picked: { airlockId: "acme-hr", version: "v10" },
    },
    {
      state: "NoVersionConfigured",
      configured: { airlockId: "acme-hr", version: "" },
      available: listing,
      picked: { airlockId: "acme-hr", version: "v10" },
    },
    {
      state: "ConfiguredAirlockGoneAndOneOther",
      configured: { airlockId: "founders-finance", version: "v1" },
      available: [listing[1]],
      picked: { airlockId: "hr-app-1", version: "pilot" },
    },
    {
      state: "ConfiguredAirlockGoneAndSeveralOthers",
      configured: { airlockId: "founders-finance", version: "v1" },
      available: listing,
      picked: { airlockId: "", version: "" },
    },
    {
      state: "AirlockWithoutVersions",
      configured: { airlockId: "acme-hr", version: "v1" },
      available: [airlock("acme-hr")],
      picked: { airlockId: "acme-hr", version: "" },
    },
  ])("given$state_whenPicked_then$picked.version", ({ configured, available, picked }) => {
    // Arrange / Act
    const chosen = pickAirlockVersion(configured, available);
    // Assert
    expect(chosen).toEqual(picked);
  });
});
