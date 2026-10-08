import { describe, expect, it } from "vitest";
import {
  type Figure,
  type Grounding,
  computedExpression,
  figureName,
  piecesOf,
  sourceLabels,
} from "../provenance";

const salary: Figure = {
  id: "f1",
  display: "78,000 EUR",
  status: "sourced",
  source: { evidence: "ev-1", entity: "hr.compensation", column: "gross_salary", row_key: "3" },
};
const quote = {
  id: "q1",
  status: "quoted" as const,
  evidence: "ev-2",
  title: "Leave policy",
  text: "carry over up to five days",
};

describe("piecesOf", () => {
  it("givenSegmentsWithAFigure_whenSplit_thenTheFigureStandsBetweenTheTexts", () => {
    // Arrange
    const g: Grounding = {
      segments: [{ text: "Your gross salary is " }, { figure: "f1" }, { text: "." }],
      figures: [salary],
    };

    // Act
    const pieces = piecesOf(g);

    // Assert
    expect(pieces).toEqual([{ text: "Your gross salary is " }, { figure: salary }, { text: "." }]);
  });

  it("givenAQuoteWrittenIntoTheText_whenSplit_thenItsQuotedWordsBecomeItsMark", () => {
    // Arrange
    const g: Grounding = {
      segments: [{ text: 'The policy says "carry over up to five days" each year.' }],
      figures: [],
      quotes: [quote],
    };

    // Act
    const pieces = piecesOf(g);

    // Assert
    expect(pieces).toEqual([{ text: "The policy says " }, { quote }, { text: " each year." }]);
  });

  it.each([{ figure: "f9" }, { table: "t9" }, { quote: "q9" }])(
    "givenAMarkThatNamesNothing_%o_whenSplit_thenItIsLeftOut",
    (mark) => {
      // Arrange
      const g: Grounding = { segments: [{ text: "A" }, mark, { text: "B" }], figures: [salary] };

      // Act
      const pieces = piecesOf(g);

      // Assert
      expect(pieces).toEqual([{ text: "A" }, { text: "B" }]);
    },
  );
});

describe("figureName", () => {
  it.each<[Figure["status"], string]>([
    ["sourced", "78,000 EUR, sourced from hr.compensation"],
    ["unsourced", "78,000 EUR, unsourced"],
    ["mismatch", "78,000 EUR, mismatch"],
  ])("givenAFigure_%s_whenNamed_thenItsDisplayAndProvenance", (status, name) => {
    // Arrange / Act
    const named = figureName({ ...salary, status });

    // Assert
    expect(named).toBe(name);
  });
});

describe("where a figure came from", () => {
  it("givenASourcedFigure_whenLabelled_thenEntityColumnAndRowKeyOnly", () => {
    // Arrange / Act
    const labels = sourceLabels(salary);

    // Assert
    expect(labels).toEqual(["hr.compensation", "gross_salary", "3"]);
  });

  it.each([
    [
      [
        { evidence: "ev-2", display: "30" },
        { evidence: "ev-2", display: "18" },
      ],
      "30 − 18",
    ],
    [
      [
        { evidence: "ev-2", column: "granted" },
        { evidence: "ev-2", column: "taken" },
      ],
      "subtract",
    ],
  ])("givenOperands_whenComputed_thenTheSumOrTheOperationsName %#", (of, expression) => {
    // Arrange / Act
    const said = computedExpression("subtract", of);

    // Assert
    expect(said).toBe(expression);
  });
});
