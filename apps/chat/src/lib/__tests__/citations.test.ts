import { describe, expect, it } from "vitest";
import { stripCitations } from "../citations";

describe("stripCitations", () => {
  it.each([
    ["Your gross salary is **78,000 EUR** [ev-1].", "Your gross salary is **78,000 EUR**."],
    [
      "30 vacation days [ev-1] and a learning budget [ev-2, ev-3].",
      "30 vacation days and a learning budget.",
    ],
    [
      "According to the handbook (evidence id: ev-1), you get 30 days.",
      "According to the handbook, you get 30 days.",
    ],
    ["Two weeks (ev-4).", "Two weeks."],
    ["- three months [ev-2]\n- two weeks [ev-2]", "- three months\n- two weeks"],
    ["Capped at 3 percent [Evidence: ev-1 (Master agreement, Pricing)].", "Capped at 3 percent."],
    ["Amount: 64,000 EUR\n\n[Evidence ID: ev-1]", "Amount: 64,000 EUR\n\n"],
    ["38 ng/L.\n[Evidence for troponin: ev-2 (row of 3 October)].", "38 ng/L.\n"],
    ["I don't know", "I don't know"],
    ["`a ? b : c`", "`a ? b : c`"],
    ["Then:\n    .bar()", "Then:\n    .bar()"],
    ["See [the ev-2 policy](https://x.example).", "See [the ev-2 policy](https://x.example)."],
    ["Wait\n...\nyes", "Wait\n...\nyes"],
    ["```\nx = [ev-1] ;\n```\nDone [ev-1].", "```\nx = [ev-1] ;\n```\nDone."],
    ["```js\nconst r = rows[ev-1]", "```js\nconst r = rows[ev-1]"],
  ])("givenAnswer %j_whenStripped_thenReads %j", (answer, shown) => {
    // Arrange / Act
    const stripped = stripCitations(answer);
    // Assert
    expect(stripped).toBe(shown);
  });
});
