import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { AIsolateLogo } from "../../components/icons";

describe("AIsolateLogo", () => {
  it("givenTheHeader_whenRendered_thenTheDesignersLogoFromTheWebsite", () => {
    // Arrange / Act
    const html = renderToStaticMarkup(createElement(AIsolateLogo));

    // Assert: the website's PNG, not an inline drawing of the name
    expect(html).toMatch(/<img[^>]*src="\/logo\/aisolate-black\.png"[^>]*alt="AIsolate"/);
    expect(html).not.toContain("<svg");
  });

  it("givenTheRailFolded_whenRendered_thenOnlyTheMarkShows", () => {
    // Arrange / Act
    const folded = renderToStaticMarkup(createElement(AIsolateLogo, { folded: true }));
    const open = renderToStaticMarkup(createElement(AIsolateLogo, { folded: false }));

    // Assert: the same image, cropped to the mark's width by its wrapper
    expect(folded).toContain("max-w-10");
    expect(open).not.toContain("max-w-10");
  });
});
