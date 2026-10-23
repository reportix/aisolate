import type { User } from "oidc-client-ts";
import { createElement, type ComponentProps } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SecretView, askedRecently } from "../SecretView";
import { SessionProvider } from "../Session";

const userWith = (roles: string[]) =>
  ({ profile: { name: "Jane Doe", realm_access: { roles } } }) as unknown as User;

const MODEL = {
  title: "View model",
  fields: [
    { label: "Id", value: "gpt" },
    { label: "Provider", value: "OpenAI-compatible" },
    { label: "URL", value: "https://api.openai.com/v1" },
    { label: "Model", value: "gpt-4o-mini" },
  ],
  keys: [{ name: "apiKey", label: "API key" }],
  onClose: () => undefined,
};

const render = (roles: string[], props: Partial<ComponentProps<typeof SecretView>> = {}) =>
  renderToStaticMarkup(
    createElement(
      SessionProvider,
      // createElement passes the children; the prop type wants them in the object.
      { user: userWith(roles) } as ComponentProps<typeof SessionProvider>,
      createElement(SecretView, { ...MODEL, secretRef: "aisolate-llm-gpt-abc123", ...props }),
    ),
  );

const labels = (html: string) => [...html.matchAll(/aria-label="([^"]+)"/g)].map((m) => m[1]);

describe("SecretView", () => {
  it("givenAnAdminAndAStoredKey_whenRendered_thenFieldsInClearAndTheKeyMaskedWithShowAndCopy", () => {
    // Arrange / Act
    const html = render(["aisolate-admin"]);

    // Assert
    expect(html).toContain("View model");
    expect(html).toContain("https://api.openai.com/v1");
    expect(html).toContain("gpt-4o-mini");
    expect(html).toContain("API key");
    expect(html).toContain("••••••••");
    expect(labels(html)).toEqual(expect.arrayContaining(["Show API key", "Copy API key"]));
    expect(html).not.toContain("<code");
    expect(html).toMatch(/<button[^>]*>Close<\/button>/);
    // One list of entries, the key under the model, as the fields above it: no Key/Value table.
    expect(html).not.toContain("<table");
    // The value grows, so the eye and the copy sit at the entry's right edge.
    expect(html).toMatch(/<dd[^>]*><span class="[^"]*\bgrow\b/);
    expect([...html.matchAll(/<dt[^>]*>([^<]+)<\/dt>/g)].map((m) => m[1])).toEqual([
      "Id",
      "Provider",
      "URL",
      "Model",
      "API key",
    ]);
  });

  it("givenADataSource_whenRendered_thenUsernameAndPasswordRowsAreMasked", () => {
    // Arrange / Act
    const html = render(["aisolate-admin"], {
      title: "View data source",
      fields: [{ label: "Id", value: "hr" }],
      keys: [
        { name: "username", label: "Username" },
        { name: "password", label: "Password" },
      ],
    });

    // Assert
    expect(labels(html)).toEqual(
      expect.arrayContaining(["Show Username", "Show Password", "Copy Password"]),
    );
    expect(html.match(/••••••••/g)).toHaveLength(2);
  });

  it("givenANonAdmin_whenRendered_thenALockAndTheHintAndNoShow", () => {
    // Arrange / Act
    const html = render([]);

    // Assert
    expect(html).toContain("••••••••");
    expect(html).toContain("Only an admin can reveal it");
    expect(labels(html)).not.toContain("Show API key");
    expect(labels(html)).not.toContain("Copy API key");
  });

  it("givenNoStoredCredential_whenRendered_thenTheEntrySaysNone", () => {
    // Arrange / Act
    const html = render(["aisolate-admin"], { secretRef: null });

    // Assert
    expect(html).toContain("API key");
    expect(html).toMatch(/<dd[^>]*>(?:<span[^>]*>){0,2}none</);
    expect(html).not.toContain("••••••••");
    expect(labels(html)).not.toContain("Show API key");
  });

  it("givenTheMask_whenRendered_thenScreenReadersHearHiddenNotEightBullets", () => {
    // Arrange / Act
    const html = render(["aisolate-admin"]);

    // Assert
    expect(html).toMatch(/<span[^>]*aria-hidden="true"[^>]*>••••••••<\/span>/);
    expect(html).toContain("Hidden");
  });
});

describe("askedRecently", () => {
  const withFlag = (value: string | null) =>
    vi.stubGlobal("sessionStorage", { getItem: () => value } as unknown as Storage);

  afterEach(() => vi.unstubAllGlobals());

  it.each([
    ["TenSecondsAgo", String(Date.now() - 10_000), true],
    ["FiveMinutesAndTenSecondsAgo", String(Date.now() - 310_000), false],
    ["Junk", "yesterday", false],
    ["None", null, false],
  ])("givenAFlagFrom%s_whenAsked_thenAskedRecentlyIs%s", (_, flag, expected) => {
    // Arrange
    withFlag(flag);

    // Act / Assert
    expect(askedRecently()).toBe(expected);
  });
});
