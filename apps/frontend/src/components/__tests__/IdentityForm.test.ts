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
