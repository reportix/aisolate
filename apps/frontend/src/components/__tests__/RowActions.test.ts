import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { RowActions, type Actions } from "../RowActions";

const noop = () => undefined;

const render = (actions: Actions) =>
  renderToStaticMarkup(
    createElement(RowActions, { actions, what: { noun: "model", id: "granite" } }),
  );

const items = (html: string) =>
  [...html.matchAll(/<(?:a|button)[^>]*>([^<]+)<\/(?:a|button)>/g)].map((m) => m[1].trim());

describe("RowActions", () => {
  it("givenARowOfTheOwnersOwn_whenRendered_thenTheMenuHasViewEditAndDelete", () => {
    // Arrange
    const actions: Actions = { onView: noop, edit: { onEdit: noop, busy: false, onRemove: noop } };

    // Act
    const html = render(actions);

    // Assert: Edit opens the form above the list, as View does, so it is a button and no link
    expect(html).toMatch(/<button[^>]*aria-label="Actions for model granite"/);
    expect(html).toContain('popover="auto"');
    expect(items(html)).toEqual(["View", "Edit", "Delete"]);
    expect(html).not.toContain("href=");
  });

  it("givenAnInheritedRow_whenRendered_thenTheMenuHasViewAlone", () => {
    // Arrange
    const actions: Actions = { onView: noop };

    // Act
    const html = render(actions);

    // Assert
    expect(items(html)).toEqual(["View"]);
    expect(html).not.toContain("Delete");
  });
});
