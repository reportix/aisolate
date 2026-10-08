import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { writeOwner } from "../scope";
import { airlockOf, spaceOf } from "./airlock.fixture";

const SPACE = spaceOf({
  caller_role: "admin",
  llms: [{ id: "granite", provider: "ollama", url: "http://ollama:11434", model: "granite4.1:8b" }],
});

const AIRLOCK = airlockOf({
  users: ["eric@example.com"],
  callers: ["acme-chat"],
  clients: ["acme-batch"],
});

describe("writeOwner", () => {
  beforeEach(() => vi.stubEnv("NEXT_PUBLIC_AISOLATE_URL", "http://backend.test"));
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it("givenAnAirlockRouteWhoseAirlockHasNotLoaded_whenWrite_thenRefusedAndTheSpaceUntouched", async () => {
    // Arrange: the space arrived, the airlock not yet
    const fetch = vi.fn(async () => Response.json(SPACE));
    vi.stubGlobal("fetch", fetch);
    const route = { spaceId: "acme", airlockId: "hr", space: SPACE, airlock: null };

    // Act
    const written = writeOwner("token", route, { users: [] });

    // Assert
    await expect(written).rejects.toThrow("The airlock has not loaded yet");
    expect(fetch).not.toHaveBeenCalled();
  });

  it("givenAnAirlockRouteWithMachineClients_whenWriteItsUsers_thenTheClientsGoBackUntouched", async () => {
    // Arrange: the clients came from a project file; the UI has no editor for them
    const fetch = vi.fn(async () => Response.json(AIRLOCK));
    vi.stubGlobal("fetch", fetch);
    const route = { spaceId: "acme", airlockId: "hr", space: SPACE, airlock: AIRLOCK };

    // Act
    await writeOwner("token", route, { users: ["nora@example.com"] });

    // Assert: the PUT replaces the list, so leaving it out would drop acme-batch
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://backend.test/v1/spaces/acme/airlocks/hr");
    expect(JSON.parse(init.body as string)).toStrictEqual({
      id: "hr",
      name: "HR",
      description: "",
      users: ["nora@example.com"],
      callers: ["acme-chat"],
      clients: ["acme-batch"],
      llms: [],
      data_sources: [],
    });
  });

  it("givenASpaceRoute_whenWriteItsLlms_thenTheWholeSpaceGoesBackWithTheLlmsReplaced", async () => {
    // Arrange
    const fetch = vi.fn(async () => Response.json(SPACE));
    vi.stubGlobal("fetch", fetch);
    const route = { spaceId: "acme", space: SPACE, airlock: null };
    const nomic = {
      id: "nomic",
      provider: "ollama",
      url: "http://ollama:11434",
      model: "nomic-embed-text",
    };

    // Act
    await writeOwner("token", route, { llms: [nomic] });

    // Assert: the server's own fields stay home
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://backend.test/v1/spaces/acme");
    expect(JSON.parse(init.body as string)).toStrictEqual({
      id: "acme",
      name: "Acme",
      description: "",
      members: SPACE.members,
      llms: [nomic],
      data_sources: [],
    });
  });
});
