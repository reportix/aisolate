import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

// The :root tokens as the browser gets them: `--name: #rrggbb`.
const css = readFileSync(new URL("../theme.css", import.meta.url), "utf8");
const root = css.slice(css.indexOf(":root"), css.indexOf("@theme"));
const tokens = Object.fromEntries(
  [...root.matchAll(/--([\w-]+):\s*(#[0-9a-f]{6});/gi)].map(([, name, hex]) => [name, hex]),
);

/** WCAG 2.x relative luminance of a #rrggbb colour. */
function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(fg: string, bg: string): number {
  const [hi, lo] = [luminance(tokens[fg]), luminance(tokens[bg])].sort((a, b) => b - a);
  return (hi + 0.05) / (lo + 0.05);
}

const grounds = [
  "bg-primary",
  "bg-secondary",
  "bg-tertiary",
  "sidebar",
  "sidebar-hover",
  "sidebar-active",
];
const inks = ["text-primary", "text-secondary", "text-tertiary"];

// Every text on every surface it sits on: 4.5:1 (WCAG 1.4.3).
const text: [string, string][] = [
  ...inks.flatMap((ink) => grounds.map((bg): [string, string] => [ink, bg])),
  ...inks.map((ink): [string, string] => [ink, "accent-blue-wash"]),
  ...inks.map((ink): [string, string] => [ink, "accent-surface"]),
  ["accent-primary", "bg-primary"],
  ["accent-primary", "accent-surface"],
  ["accent-primary", "accent-surface-strong"],
  ["warn", "bg-primary"],
  ["warn", "warn-wash"],
  ["error", "bg-primary"],
  ["error", "error-wash"],
  ["ok", "bg-primary"],
  ["ok", "ok-wash"],
];

// A control's edge, the focus outline and the send arrow: 3:1 (WCAG 1.4.11).
const graphics: [string, string][] = [
  ["border-focus", "bg-primary"],
  ["border-focus", "sidebar"],
  ["text-inverse", "accent-blue"],
  ["text-inverse", "accent-blue-hover"],
  ["border-control", "bg-primary"],
  ["border-control", "bg-secondary"],
];

describe("theme contrast", () => {
  it.each(text)("givenText%sOn%s_whenMeasured_thenAtLeastAA", (fg, bg) => {
    // Arrange
    expect(tokens[fg] && tokens[bg]).toBeTruthy();

    // Act
    const ratio = contrast(fg, bg);

    // Assert
    expect(ratio).toBeGreaterThanOrEqual(4.5);
  });

  it.each(graphics)("givenGraphic%sOn%s_whenMeasured_thenAtLeast3To1", (fg, bg) => {
    // Arrange
    expect(tokens[fg] && tokens[bg]).toBeTruthy();

    // Act
    const ratio = contrast(fg, bg);

    // Assert
    expect(ratio).toBeGreaterThanOrEqual(3);
  });
});
