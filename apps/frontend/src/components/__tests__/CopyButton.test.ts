import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { CopyButton } from "../CopyButton";

describe("CopyButton", () => {
  it("givenATitle_whenCopyButtonRendered_thenTheTooltipIsTheTitleNotTheText", () => {
    // Arrange / Act
    const html = renderToStaticMarkup(
      createElement(CopyButton, { text: "hunter2", title: "Copy" }),
    );
    // Assert
    expect(html).toContain('title="Copy"');
    expect(html).not.toContain("hunter2");
  });
});
