import { describe, expect, it } from "vitest";
import { checkLine } from "../trail";

describe("checkLine", () => {
  it.each([
    [
      "OwnRecordVerifier",
      "FAIL",
      "the answer gives Eric Lindholm's figure for a question about your own record",
      "Own record verifier: the answer gives Eric Lindholm's figure for a question about your own record",
    ],
    [
      "CustomerVerifier",
      "PASS",
      "every figure is about Northwind Bank AG",
      "Customer verifier: every figure is about Northwind Bank AG",
    ],
    ["SalaryVerifier", "WARN", "  ", "Salary verifier: warning"],
    ["SalaryVerifier", "PASS", undefined, "Salary verifier: pass"],
  ] as const)("given%s%sAndNote %j_whenShown_thenAPlainLine", (handler, status, note, line) => {
    // Arrange
    const verdict = { handler, status, note };

    // Act
    const shown = checkLine(verdict);

    // Assert
    expect(shown).toBe(line);
  });
});
