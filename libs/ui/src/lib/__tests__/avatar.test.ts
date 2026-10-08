import { describe, expect, it } from "vitest";
import { initialOf } from "../avatar";

describe("initialOf", () => {
  it.each([
    ["nora", "N"],
    ["  ivy", "I"],
    ["équipe", "É"],
    ["   ", "?"],
    [null, "?"],
  ])("givenName_%j_whenInitial_thenUppercasedFirstLetterOrQuestionMark", (name, expected) => {
    // Arrange / Act
    const initial = initialOf(name);

    // Assert
    expect(initial).toBe(expected);
  });
});
