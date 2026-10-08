import { describe, expect, it } from "vitest";
import { tileHue } from "../tile";

describe("tileHue", () => {
  it("givenThreeIds_whenHued_thenEachStableInRangeAndDistinct", () => {
    // Arrange
    const ids = ["hr-acme", "finance", "acme-hr"];

    // Act
    const hues = ids.map(tileHue);

    // Assert
    expect(ids.map(tileHue)).toEqual(hues);
    for (const hue of hues) {
      expect(Number.isInteger(hue) && hue >= 0 && hue < 360).toBe(true);
    }
    expect(new Set(hues).size).toBe(hues.length);
  });
});
