import { Unauthorized } from "@/ui";
import { afterEach, describe, expect, it, vi } from "vitest";
import { listAirlocks, streamChat } from "../api";
import type { StreamEvent } from "../types";

const SETTINGS = { aisolateUrl: "http://localhost:8080", airlockId: "acme-hr" };

const sse = (text: string) =>
  new Response(text, { status: 200, headers: { "Content-Type": "text/event-stream" } });
const ENDPOINT = "http://localhost:8080/v1/spaces/hr-acme/airlocks/acme-hr/chat";
const AIRLOCKS = [
  {
    id: "acme-hr",
    name: "HR",
    description: "Pay and documents.",
    endpoint: ENDPOINT,
    tools: ["read_records", "aggregate_records"],
  },
  {
    id: "drafts",
    name: "Drafts",
    description: "",
    endpoint: ENDPOINT.replace("/acme-hr/", "/drafts/"),
    tools: [],
  },
];
const listing = () =>
  new Response(JSON.stringify(AIRLOCKS), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });

describe("streamChat", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("givenAirlockInSettings_whenStreamChat_thenPostsToItsEndpointWithBearerAndEventStream", async () => {
    // Arrange
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(listing())
      .mockResolvedValueOnce(sse('data:{"type":"done","evidence_ids":[],"grounded":true}\n\n'));
    vi.stubGlobal("fetch", fetchMock);
    const events: StreamEvent[] = [];
    const controller = new AbortController();
    // Act
    await streamChat(
      "What is my salary?",
      "t1",
      controller.signal,
      (e) => events.push(e),
      SETTINGS,
    );
    // Assert
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/v1/airlocks");
    const [url, init] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(url).toBe(ENDPOINT);
    expect(init.method).toBe("POST");
    expect(init.headers).toEqual({
      Authorization: "Bearer t1",
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    });
    expect(init.body).toBe(JSON.stringify({ message: "What is my salary?" }));
    expect(events.map((e) => e.type)).toEqual(["done"]);
    controller.abort();
    expect(init.signal?.aborted).toBe(true);
  });

  it("givenAStreamSilentForTwoMinutes_whenTheWatchdogFires_thenTheTurnFailsAndTheRequestIsAborted", async () => {
    // Arrange
    vi.useFakeTimers();
    const silent = new Response(new ReadableStream(), { status: 200 });
    const fetchMock = vi.fn().mockResolvedValueOnce(listing()).mockResolvedValueOnce(silent);
    vi.stubGlobal("fetch", fetchMock);
    const turn = streamChat("hi", "t1", new AbortController().signal, () => {}, SETTINGS);
    const failed = expect(turn).rejects.toThrow("The assistant stopped responding");
    // Act
    await vi.advanceTimersByTimeAsync(120_000);
    // Assert
    await failed;
    const [, init] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(init.signal?.aborted).toBe(true);
  });

  it("givenEndpointOnAnotherHost_whenStreamChat_thenTheTokenIsNotSentThere", async () => {
    // Arrange
    const foreign = new Response(
      JSON.stringify([
        {
          id: "acme-hr",
          name: "HR",
          description: "",
          endpoint: "https://evil.test/chat",
          tools: [],
        },
      ]),
      { status: 200 },
    );
    const fetchMock = vi.fn().mockResolvedValueOnce(foreign);
    vi.stubGlobal("fetch", fetchMock);
    // Act
    const act = streamChat("hi", "t1", new AbortController().signal, () => {}, SETTINGS);
    // Assert
    await expect(act).rejects.toMatchObject({ status: 502 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("givenAirlockNotListedForTheUser_whenStreamChat_thenRejectsWith404", async () => {
    // Arrange
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(listing()));
    // Act
    const act = streamChat("hi", "t1", new AbortController().signal, () => {}, {
      aisolateUrl: "http://localhost:8080",
      airlockId: "founders-finance",
    });
    // Assert
    await expect(act).rejects.toMatchObject({ status: 404 });
  });

  it("givenA401_whenStreamChat_thenRejectsWithUnauthorized", async () => {
    // Arrange
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(null, { status: 401 })),
    );
    // Act
    const act = streamChat("hi", "expired", new AbortController().signal, () => {}, {
      aisolateUrl: "http://aisolate.test",
      airlockId: "hr-app-1",
    });
    // Assert
    await expect(act).rejects.toBeInstanceOf(Unauthorized);
  });

  it("givenA403Problem_whenStreamChat_thenRejectsWithAnApiErrorCarryingItsDetail", async () => {
    // Arrange
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ status: 403, detail: "Role not allowed" }), {
            status: 403,
            headers: { "Content-Type": "application/problem+json" },
          }),
      ),
    );
    // Act
    const act = streamChat("hi", "t1", new AbortController().signal, () => {}, {
      aisolateUrl: "http://aisolate.test",
      airlockId: "hr-app-1",
    });
    // Assert
    await expect(act).rejects.toMatchObject({
      status: 403,
      message: "Role not allowed",
    });
  });
});

describe("listAirlocks", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("givenListing_whenListAirlocks_thenEveryAirlockItsTokenAdmitsInAIsolatesOrder", async () => {
    // Arrange
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(listing()));
    // Act
    const airlocks = await listAirlocks("t1", "http://localhost:8080");
    // Assert
    expect(airlocks).toEqual(AIRLOCKS);
  });
});
