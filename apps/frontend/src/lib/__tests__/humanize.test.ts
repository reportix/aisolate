import { describe, expect, it } from "vitest";
import { humanize } from "../humanize";

describe("humanize", () => {
  it.each([
    ["HrDocumentsContextPreparator", "Hr documents context preparator"],
    ["LLMRouter", "LLM router"],
    ["Level2Verifier", "Level 2 verifier"],
  ])("givenClassName_%s_whenHumanize_thenWords_%s", (name, expected) => {
    // Arrange / Act
    const title = humanize(name);

    // Assert
    expect(title).toBe(expected);
  });
});
