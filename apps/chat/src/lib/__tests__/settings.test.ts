import type { AirlockListing } from "@/lib/api";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loadSettings, pickAirlock, saveSettings } from "../settings";

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
    expect(s.aisolateUrl).toBe("http://localhost:8080");
    expect(s.airlockId).toBe("acme-hr");
  });

  it("givenSavedSettings_whenLoaded_thenTheAirlockRoundTripsWithTheEnvironmentsUrl", () => {
    // Arrange
    const storage = memoryStorage();
    saveSettings({ aisolateUrl: "https://aisolate.example.com", airlockId: "hr-app-1" }, storage);
    // Act
    const s = loadSettings(storage);
    // Assert
    expect(s).toEqual({ aisolateUrl: "http://localhost:8080", airlockId: "hr-app-1" });
  });

  it("givenSettingsAnOlderImageStoredWithAVersion_whenLoaded_thenTheVersionIsDropped", () => {
    // Arrange
    const storage = memoryStorage();
    storage.setItem(
      "aisolate.settings",
      JSON.stringify({ airlockId: "hr-app-1", version: "pilot" }),
    );
    // Act
    const s = loadSettings(storage);
    // Assert
    expect(s).toEqual({ aisolateUrl: "http://localhost:8080", airlockId: "hr-app-1" });
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
  const airlock = (id: string): AirlockListing => ({
    id,
    name: id,
    description: "",
    endpoint: "",
    tools: [],
  });
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
