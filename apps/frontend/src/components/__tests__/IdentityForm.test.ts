import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { IdentityForm, type Identity } from "../IdentityForm";

const form = (
  initial: Identity,
  id: "new" | "fixed",
  url = { label: "Space URL", path: "/spaces/" },
) =>
  renderToStaticMarkup(
    createElement(IdentityForm, {
      initial,
      id,
      url,
      label: "Save",
      errors: [],
      busy: false,
      onSubmit: () => undefined,
      onCancel: () => undefined,
    }),
  );

const GRANITE = {
  id: "granite",
  provider: "ollama",
  url: "http://ollama:11434",
  model: "granite4.1:8b",
};

const versionForm = (initial: Identity, nameReadOnly = false) =>
  renderToStaticMarkup(
    createElement(IdentityForm, {
      initial,
      versionName: true,
      nameReadOnly,
      llms: nameReadOnly ? undefined : [GRANITE],
      label: "Save",
      errors: [],
      busy: false,
      onSubmit: () => undefined,
      onCancel: () => undefined,
    }),
  );

const labels = (html: string) => [...html.matchAll(/<label[^>]*>([^<]+)/g)].map((m) => m[1].trim());
const values = (html: string) => [...html.matchAll(/<input[^>]*value="([^"]*)"/g)].map((m) => m[1]);

describe("IdentityForm of a space or an airlock", () => {
  it.each([
    ["Space", { label: "Space URL", path: "/spaces/" }],
    ["Airlock", { label: "Airlock URL", path: "/spaces/acme/airlocks/" }],
  ] as const)("given%s_whenCreated_thenNameThenOptionalDescription_noUrl", (_, url) => {
    // Arrange
    const initial = { id: "", name: "", description: "" };

    // Act
    const html = form(initial, "new", url);

    // Assert
    expect(labels(html)).toEqual(["Name", "Description"]);
    expect(html.match(/\(optional\)/g)).toHaveLength(1);
    expect(html).not.toContain(url.path);
  });

  it.each([
    ["Space", { label: "Space URL", path: "/spaces/" }],
    ["Airlock", { label: "Airlock URL", path: "/spaces/acme/airlocks/" }],
  ] as const)("given%s_whenEdited_thenNameThenReadOnlyUrlThenDescription", (_, url) => {
    // Arrange
    const initial = { id: "x", name: "X", description: "" };

    // Act
    const html = form(initial, "fixed", url);

    // Assert
    expect(labels(html)).toEqual(["Name", url.label, "Description"]);
    expect(html).toContain(url.path);
  });

  it.each([
    ["Space", "acme", "Acme Financial Group"],
    ["Airlock", "hr", "HR"],
  ])("given%s_whenEdited_thenTheNameAndTheReadOnlyIdShow", (_, entityId, name) => {
    // Arrange
    const initial = { id: entityId, name, description: "" };

    // Act
    const html = form(initial, "fixed");

    // Assert
    expect(values(html)).toEqual([name, entityId]);
    const idInput = html.match(new RegExp(`<input[^>]*value="${entityId}"[^>]*>`))?.[0] ?? "";
    expect(idInput.toLowerCase()).toContain("readonly");
  });
});

describe("IdentityForm of a version", () => {
  it("givenANewVersion_whenCreated_thenNameThenTheModelToPickThenOptionalDescription", () => {
    // Arrange
    const initial = { name: "", description: "" };

    // Act
    const html = versionForm(initial);

    // Assert
    expect(labels(html)).toEqual(["Name", "Model", "Description"]);
    expect(html).toContain("granite · granite4.1:8b");
    expect(html).toContain("names it in version");
  });

  it("givenAVersion_whenEdited_thenItsNameIsReadOnlyAndNoModelIsAsked", () => {
    // Arrange
    const initial = { name: "v1", description: "First version." };

    // Act
    const html = versionForm(initial, true);

    // Assert
    expect(labels(html)).toEqual(["Name", "Description"]);
    const nameInput = html.match(/<input[^>]*value="v1"[^>]*>/)?.[0] ?? "";
    expect(nameInput.toLowerCase()).toContain("readonly");
  });
});
