import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const globals = readFileSync(new URL("../../app/globals.css", import.meta.url), "utf8");

describe("globals.css", () => {
  // Tailwind 4's preflight leaves a button on the arrow (v3 gave it the hand), while a link keeps
  // the hand from the browser: without this rule the cursor says "clickable" on some controls only.
  it("givenTheGlobalStylesheet_whenRead_thenEveryEnabledButtonTakesThePointer", () => {
    // Arrange / Act
    const rule = /button:not\(:disabled\)[^{]*\{[^}]*cursor:\s*pointer/;

    // Assert
    expect(globals).toMatch(rule);
  });
});
