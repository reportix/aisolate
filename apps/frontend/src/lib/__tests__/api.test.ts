import { ApiError, Unauthorized, Unreachable } from "@aisolate/ui";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { STALE, api, clusterTookLastWrite, onApiError } from "../api";
import { forgetAndLogin } from "../auth";
import { reportDown } from "../connection";
import type { ExportFormat } from "../audit";
import type { AirlockForm, DataSourceInput, SpaceForm } from "../types";

// The sign-in itself needs a browser; only whether a 401 starts it matters here.
vi.mock("../auth", () => ({ forgetAndLogin: vi.fn(async () => {}) }));
vi.mock("../connection", () => ({ reportDown: vi.fn() }));

const GRANITE = {
  id: "granite",
  provider: "ollama",
  url: "http://ollama:11434",
  model: "granite4.1:8b",
};

/** An OpenAI-compatible model whose API key's Secret is stored: no key typed. */
const GPT = {
  id: "gpt",
  provider: "openai",
  url: "https://api.openai.com/v1",
  model: "gpt-4o-mini",
  secret_ref: "aisolate-llm-gpt-a1b2c3",
};

/** A row whose Secret is stored: no credentials typed. */
const stored: DataSourceInput = {
  id: "acme_hr",
  url: "jdbc:postgresql://acme-db:5432/acme_hr",
  secret_ref: "hr-acme-hr-assistant-v1-ds-acme-hr-x7k2qa",
  username: "",
  password: "",
};

const space: SpaceForm = {
  id: "hr-acme",
  name: "HR Acme",
  description: "",
  members: [{ email: "eric@example.com", role: "user" }],
  llms: [GRANITE],
  data_sources: [],
};

const airlock: AirlockForm = {
  id: "hr-assistant",
  name: "HR assistant",
  description: "",
  users: ["eric@example.com", "jasmine@example.com"],
  callers: ["acme-chat"],
  clients: [],
  llms: [],
  data_sources: [],
};

const BASE = "http://backend.test/v1/spaces/hr-acme";
const READ_AT = "2026-10-17T09:30:00.123456Z";

/** The fetch stub's one call: its URL and init. */
function stubFetch(response: () => Response = () => Response.json({})) {
  const fetch = vi.fn(async () => response());
  vi.stubGlobal("fetch", fetch);
  return () => fetch.mock.calls[0] as unknown as [string, RequestInit];
}

beforeEach(() => vi.stubEnv("NEXT_PUBLIC_AISOLATE_URL", "http://backend.test"));
afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("call", () => {
  it.each([
    ["TheBackendIsDown", true, "server"],
    ["TheDeviceIsOffline", false, "offline"],
  ])("given%s_whenACallIsMade_thenUnreachableNotTheBrowsersMessage", async (_, onLine, kind) => {
    // Arrange
    vi.stubGlobal("fetch", () => Promise.reject(new TypeError("Failed to fetch")));
    vi.stubGlobal("navigator", { onLine });

    // Act
    const e = (await api.spaces.list("token").catch((x: unknown) => x)) as Unreachable;

    // Assert
    expect(e).toBeInstanceOf(Unreachable);
    expect(e.kind).toBe(kind);
    expect(e.message).not.toContain("fetch");
  });

  it.each([
    [
      "ASpaceUpdate",
      () => api.spaces.update("token", space, READ_AT),
      "PUT",
      "/spaces/hr-acme",
      space,
    ],
    [
      "AnAirlockUpdate",
      () => api.airlocks.update("token", "hr-acme", airlock, READ_AT),
      "PUT",
      "/spaces/hr-acme/airlocks/hr-assistant",
      airlock,
    ],
    [
      "AnAirlockGet",
      () => api.airlocks.get("token", "hr-acme", "hr-assistant"),
      "GET",
      "/spaces/hr-acme/airlocks/hr-assistant",
      undefined,
    ],
    [
      "ADataErrorRead",
      () => api.audit.dataError("token", "hr-acme", "hr-assistant"),
      "GET",
      "/audit/data-error?space=hr-acme&airlock=hr-assistant",
      undefined,
    ],
    ["AnLlmList", () => api.llms.list("token"), "GET", "/llms", undefined],
    ["AnLlmCreate", () => api.llms.create("token", GRANITE), "POST", "/llms", GRANITE],
    [
      "AnLlmEdit",
      () => api.llms.update("token", GRANITE, READ_AT),
      "PUT",
      "/llms/granite",
      GRANITE,
    ],
    [
      "AnLlmCreateWithATypedKey",
      () => api.llms.create("token", { ...GPT, secret_ref: "", api_key: " sk-test " }),
      "POST",
      "/llms",
      { id: "gpt", provider: "openai", url: GPT.url, model: GPT.model, api_key: "sk-test" },
    ],
    [
      "AnLlmEditKeepingItsKey",
      () => api.llms.update("token", { ...GPT, api_key: "" }, READ_AT),
      "PUT",
      "/llms/gpt",
      GPT,
    ],
    [
      "AnLlmDelete",
      () => api.llms.remove("token", "granite"),
      "DELETE",
      "/llms/granite",
      undefined,
    ],
    [
      "ADataSourceCreate",
      () => api.dataSources.create("token", { ...stored, secret_ref: "", password: "secret" }),
      "POST",
      "/data-sources",
      { id: stored.id, url: stored.url, password: "secret" },
    ],
    [
      "ADataSourceEditKeepingItsSecret",
      () => api.dataSources.update("token", stored, READ_AT),
      "PUT",
      "/data-sources/acme_hr",
      { id: stored.id, url: stored.url, secret_ref: stored.secret_ref },
    ],
    [
      "ADataSourceDelete",
      () => api.dataSources.remove("token", "acme_hr"),
      "DELETE",
      "/data-sources/acme_hr",
      undefined,
    ],
  ])(
    "given%s_whenCalled_thenItsMethodPathBodyAndWhatASaveWasReadAt",
    async (_, send, method, path, body) => {
      // Arrange
      const call = stubFetch(() =>
        method === "DELETE" ? new Response(null, { status: 204 }) : Response.json({}),
      );

      // Act
      await send();

      // Assert: a save names what it was read at, so the backend refuses it when another came between
      const [sent, init] = call();
      expect(sent).toBe(`http://backend.test/v1${path}`);
      expect(init.method).toBe(method);
      expect((init.headers as Record<string, string>)["If-Match"]).toBe(
        method === "PUT" ? `"${READ_AT}"` : undefined,
      );
      expect(init.body === undefined ? undefined : JSON.parse(init.body as string)).toStrictEqual(
        body,
      );
    },
  );

  it("givenAToken_whenACallIsMade_thenItIsSentAsABearer", async () => {
    // Arrange
    const call = stubFetch();

    // Act
    await api.spaces.list("token");

    // Assert
    const [, init] = call();
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer token");
  });

  it.each([
    ["Applied", true, { "AIsolate-Cluster": "applied" }],
    ["NotApplied", false, { "AIsolate-Cluster": "not applied" }],
    ["Absent", true, {}],
  ])("givenTheClusterAnswer%s_whenSaved_thenTookLastWriteIs%s", async (_, took, headers) => {
    // Arrange
    stubFetch(() => Response.json({}, { headers }));

    // Act
    await api.spaces.update("token", space, READ_AT);

    // Assert
    expect(clusterTookLastWrite()).toBe(took);
  });

  it.each([
    ["Space", () => api.spaces.create("token", space), "http://backend.test/v1/spaces", space],
    [
      "Airlock",
      () => api.airlocks.create("token", "hr-acme", airlock),
      `${BASE}/airlocks`,
      airlock,
    ],
  ])("givenANew%s_whenCreated_thenPostedAsJsonWithoutId", async (_, send, url, form) => {
    // Arrange
    const call = stubFetch();

    // Act
    await send();

    // Assert: the backend makes the id from the name
    const [sent, init] = call();
    expect(sent).toBe(url);
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
    const body = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(body).not.toHaveProperty("id");
    expect(body).toEqual({ ...form, id: undefined });
  });
});

describe("a space's models", () => {
  it("givenARowTheBodyChanges_whenASpaceIsSaved_thenItsModelsGoThroughTheRowBody", async () => {
    // Arrange: this case alone pins that a typed key never travels with the Secret it replaces
    // (llmBody's `else if (row.secret_ref)`): the other branches are pinned above, so do not cut
    // it as a duplicate
    const form: SpaceForm = { ...space, llms: [{ ...GPT, api_key: "sk-new" }] };
    const call = stubFetch();

    // Act
    await api.spaces.update("token", form, READ_AT);

    // Assert
    const [, init] = call();
    const body = JSON.parse(init.body as string) as SpaceForm;
    expect(body.llms).toStrictEqual([
      { id: "gpt", provider: "openai", url: GPT.url, model: GPT.model, api_key: "sk-new" },
    ]);
  });
});

describe("audit export", () => {
  const NOW = Date.parse("2026-10-19T12:00:00Z");

  it.each<[ExportFormat, string | null, string]>([
    [
      "pdf",
      'attachment; filename="aisolate-audit-2026-10-19.pdf"',
      "aisolate-audit-2026-10-19.pdf",
    ],
    [
      "json",
      'attachment; filename="aisolate-audit-2026-10-19.json"',
      "aisolate-audit-2026-10-19.json",
    ],
    [
      "xbrl",
      'attachment; filename="aisolate-audit-2026-10-19.xbrl"',
      "aisolate-audit-2026-10-19.xbrl",
    ],
    // A backend on another origin that does not expose the header
    ["xbrl", null, "aisolate-audit.xbrl"],
  ])(
    "given%s_whenExported_thenTheListsQueryItsFormatAndTheFileTheBackendNames",
    async (format, disposition, filename) => {
      // Arrange
      const call = stubFetch(
        () =>
          new Response("file", {
            headers: disposition ? { "Content-Disposition": disposition } : {},
          }),
      );

      // Act
      const file = await api.audit.export("token", format, { q: "elena", period: "24h" }, NOW);

      // Assert: the list's own query, then the format
      const [sent] = call();
      expect(sent).toBe(
        `http://backend.test/v1/audit/export?q=elena&from=2026-10-18T12%3A00%3A00.000Z&format=${format}`,
      );
      expect(file.filename).toBe(filename);
      expect(await file.blob.text()).toBe("file");
    },
  );

  it("givenAnEvent_whenExported_thenItsOwnAddressAndTheFileTheBackendNames", async () => {
    // Arrange
    const call = stubFetch(
      () =>
        new Response("file", {
          headers: { "Content-Disposition": 'attachment; filename="aisolate-audit-event-15.xbrl"' },
        }),
    );

    // Act
    const file = await api.audit.exportEvent("token", "15", "xbrl");

    // Assert
    const [sent] = call();
    expect(sent).toBe("http://backend.test/v1/audit/events/15/export?format=xbrl");
    expect(file.filename).toBe("aisolate-audit-event-15.xbrl");
  });
});

describe("onApiError", () => {
  it("givenA401_whenHandled_thenAFreshSignInStartsAndNoBannerShows", () => {
    // Arrange
    const banner = vi.fn();

    // Act
    onApiError(new Unauthorized(), "Could not save", { banner });

    // Assert
    expect(forgetAndLogin).toHaveBeenCalledOnce();
    expect(banner).not.toHaveBeenCalled();
  });

  it("givenTheBackendUnreachable_whenHandled_thenTheUnavailableOverlayTakesOverAndNoBannerShows", () => {
    // Arrange
    const banner = vi.fn();

    // Act
    onApiError(new Unreachable("server"), "Could not save", { banner });

    // Assert
    expect(reportDown).toHaveBeenCalledWith("server");
    expect(banner).not.toHaveBeenCalled();
  });

  it.each([
    ["ASpaceOrAirlockSavedSince", 409, "updated_at: Saved by someone else since you opened it."],
    ["ARevisionSavedSince", 409, "revision: Saved by someone else since you opened it."],
    ["APreconditionFailed", 412, "If-Match: does not match"],
  ])("given%s_whenHandled_thenTheReloadBannerNotAFormError", (_, status, error) => {
    // Arrange
    const banner = vi.fn();
    const errors = vi.fn();

    // Act
    onApiError(new ApiError(status, "conflict", [error]), "Could not save", { banner, errors });

    // Assert
    expect(banner).toHaveBeenCalledWith(STALE);
    expect(errors).not.toHaveBeenCalled();
  });

  it("givenFieldErrorsAndAForm_whenHandled_thenTheyGoBackToTheFormNotTheBanner", () => {
    // Arrange
    const banner = vi.fn();
    const errors = vi.fn();

    // Act
    onApiError(new ApiError(400, "invalid form", ["name: required"]), "Could not save", {
      banner,
      errors,
    });

    // Assert
    expect(errors).toHaveBeenCalledWith(["name: required"]);
    expect(banner).not.toHaveBeenCalled();
  });
});

describe("credentials.reveal", () => {
  it("givenAStoredSecret_whenRevealed_thenItPostsToTheRevealPathWithTheToken", async () => {
    // Arrange
    const sent = stubFetch(() => Response.json({ values: { apiKey: "k" } }));

    // Act
    const answer = await api.credentials.reveal("token", "hr-ds-acme-hr-abc123");

    // Assert
    const [url, init] = sent();
    expect(url).toBe("http://backend.test/v1/credentials/hr-ds-acme-hr-abc123/reveal");
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer token");
    expect(init.body).toBeUndefined();
    expect(answer.values).toEqual({ apiKey: "k" });
  });

  it("givenARevealAnswerWithoutTheClusterHeader_thenTheClusterFlagIsUntouched", async () => {
    // Arrange
    stubFetch(() => Response.json({}, { headers: { "AIsolate-Cluster": "not applied" } }));
    await api.llms.remove("token", "gpt");
    stubFetch(() => Response.json({ values: {} }));

    // Act
    await api.credentials.reveal("token", "ref");

    // Assert
    expect(clusterTookLastWrite()).toBe(false);
  });
});
