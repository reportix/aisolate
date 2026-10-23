import type { User } from "oidc-client-ts";
import { createElement, type ComponentProps } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { LlmInput } from "@/lib/types";
import { LlmForm } from "../LlmForm";
import { LlmTable, llmView } from "../LlmTable";
import { SessionProvider } from "../Session";

const OLLAMA: LlmInput = {
  id: "granite",
  provider: "ollama",
  url: "http://ollama:11434",
  model: "granite4.1:8b",
  secret_ref: "",
};

const OPENAI: LlmInput = {
  id: "gpt",
  provider: "openai",
  url: "https://api.openai.com/v1",
  model: "gpt-4o-mini",
  secret_ref: "",
};

const admin = {
  profile: { name: "Jane Doe", realm_access: { roles: ["aisolate-admin"] } },
} as unknown as User;

// Rendered in an admin's session: an edit of a stored key shows no reveal of its own.
const form = (row: LlmInput) =>
  renderToStaticMarkup(
    createElement(
      SessionProvider,
      // createElement passes the children; the prop type wants them in the object.
      { user: admin } as ComponentProps<typeof SessionProvider>,
      createElement(LlmForm, {
        rows: [row],
        index: 0,
        edit: true,
        label: "Save model",
        errors: [],
        busy: false,
        onSubmit: () => undefined,
        onCancel: () => undefined,
      }),
    ),
  );

const labels = (html: string) => [...html.matchAll(/<label[^>]*>([^<]+)/g)].map((m) => m[1].trim());
const options = (html: string) =>
  [...html.matchAll(/<option[^>]*value="([^"]*)"[^>]*>([^<]+)/g)].map((m) => [m[1], m[2]]);

describe("LlmForm", () => {
  it("givenAStoredKeyOnEdit_whenRenderedForAnAdmin_thenNoRevealTheListsViewOwnsIt", () => {
    // Arrange
    const row = { ...OPENAI, secret_ref: "aisolate-llm-gpt-abc123" };

    // Act
    const html = form(row);

    // Assert
    expect(html).not.toContain("Stored encrypted");
    expect(html).not.toMatch(/<button[^>]*>.*Show<\/button>/);
  });

  it.each([
    ["Ollama", OLLAMA, ["Id", "Provider", "URL", "Model"]],
    ["OpenAiCompatible", OPENAI, ["Id", "Provider", "URL", "Model", "API key"]],
  ])("given%s_whenShown_thenAnApiKeyFieldOnlyForAnOpenAiCompatibleModel", (_, row, expected) => {
    // Arrange
    const shown = { ...row };

    // Act
    const html = form(shown);

    // Assert
    expect(labels(html)).toEqual(expected);
  });

  it("givenAnyModel_whenShown_thenTheProvidersByTheirWordsWithTheirIdsAsValues", () => {
    // Arrange
    const shown = { ...OLLAMA };

    // Act
    const html = form(shown);

    // Assert
    expect(options(html)).toEqual([
      ["ollama", "Ollama"],
      ["openai", "OpenAI-compatible"],
    ]);
  });

  it("givenAStoredProviderTheListLacks_whenShown_thenItStaysListedByItsId", () => {
    // Arrange
    const shown = { ...OLLAMA, provider: "vllm" };

    // Act
    const html = form(shown);

    // Assert
    expect(options(html).map(([value]) => value)).toEqual(["ollama", "openai", "vllm"]);
  });

  it.each([
    [
      "NoStoredKey",
      "",
      "Stored encrypted; an admin can see it from View. Leave blank for a server that needs none.",
    ],
    ["AStoredKey", "aisolate-llm-gpt-a1b2c3", "Leave blank to keep it."],
  ])("given%s_whenShown_thenTheApiKeySaysSo", (_, secretRef, help) => {
    // Arrange
    const shown = { ...OPENAI, secret_ref: secretRef };

    // Act
    const html = form(shown);

    // Assert
    expect(html).toContain(help);
    expect(html).toMatch(/<input[^>]*type="password"[^>]*>/);
    expect(html).not.toContain(secretRef || "aisolate-llm");
  });
});

describe("LlmTable", () => {
  it("givenModelsOfBothProviders_whenListed_thenIdProviderAndModelAlone", () => {
    // Arrange
    const rows = [OLLAMA, { ...OPENAI, secret_ref: "aisolate-llm-gpt-a1b2c3" }];

    // Act
    const html = renderToStaticMarkup(createElement(LlmTable, { rows }));

    // Assert: the URL and the key are the View's
    const cells = [...html.matchAll(/<td[^>]*>(?:<span[^>]*>)?([^<]*)/g)].map((m) => m[1]);
    expect(cells).toEqual([
      "granite",
      "Ollama",
      "granite4.1:8b",
      "gpt",
      "OpenAI-compatible",
      "gpt-4o-mini",
    ]);
    expect(html).not.toContain("Kept as a Secret");
  });

  it("givenActions_whenListed_thenAnActionsColumnWithAMenuOnEveryRowAndEditOnTheOwnersOwn", () => {
    // Arrange
    const rows = [OLLAMA, { ...OPENAI, secret_ref: "aisolate-llm-gpt-a1b2c3" }];
    const actions = {
      onView: () => undefined,
      edit: { onEdit: () => undefined, busy: false, onRemove: () => undefined },
    };

    // Act
    const html = renderToStaticMarkup(
      createElement(LlmTable, { rows, actions, editable: (r) => r.id === "gpt" }),
    );

    // Assert: two menus, one Edit (gpt's)
    expect(html).toMatch(/<th[^>]*>Actions<\/th>/);
    expect(html.match(/aria-label="Actions for model/g)).toHaveLength(2);
    expect(html.match(/>Edit</g)).toHaveLength(1);
  });

  it("givenAModelWithAKey_whenViewed_thenItsFieldsInTheProvidersWordsAndItsKey", () => {
    // Arrange
    const row = { ...OPENAI, secret_ref: "aisolate-llm-gpt-a1b2c3" };

    // Act
    const view = llmView(row);

    // Assert
    expect(view).toEqual({
      title: "View model",
      fields: [
        { label: "Id", value: "gpt" },
        { label: "Provider", value: "OpenAI-compatible" },
        { label: "URL", value: "https://api.openai.com/v1" },
        { label: "Model", value: "gpt-4o-mini" },
      ],
      secretRef: "aisolate-llm-gpt-a1b2c3",
      keys: [{ name: "apiKey", label: "API key" }],
    });
  });

  it("givenAModelWithoutAKey_whenViewed_thenNoSecretRef", () => {
    // Arrange / Act
    const view = llmView(OLLAMA);

    // Assert
    expect(view.secretRef).toBeNull();
  });
});
