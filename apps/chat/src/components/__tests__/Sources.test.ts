import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { elenaSalary } from "@/lib/__tests__/answers.fixture";
import { quotes } from "@/lib/__tests__/grounding.fixture";
import type { Evidence, Message } from "@/lib/types";
import { AssistantMessage } from "../AssistantMessage";

const [, elena] = elenaSalary;
const render = (message: Message): string =>
  renderToStaticMarkup(createElement(AssistantMessage, { message }));
/** What the reader sees: the text without the markup. */
const text = (html: string) => html.replace(/<[^>]+>/g, "").replaceAll("&#x27;", "'");

const row = {
  self: true,
  employee_id: 17,
  full_name: "Elena Varga",
  gross_salary: 78000,
  currency: "EUR",
};
/** Elena's answer with the rows the airlock now sends under the evidence. */
const withRows = (more: Partial<Evidence> = {}): Message => ({
  ...elena,
  evidence: [{ ...elena.evidence[0], labels: { row_key: "employee_id" }, rows: [row], ...more }],
});

const letter: Message = {
  id: "a",
  role: "assistant",
  status: "done",
  content: "",
  toolCalls: [],
  evidence: [
    {
      id: "ev-5",
      call_id: null as unknown as string,
      tool: "Read engagement letters",
      entity: "Read engagement letters",
      row_count: 3,
      columns: ["title", "section"],
      rule_version: "",
      rules: [],
      sql_hash: "",
      passages: [
        { title: "Engagement letter", section: "Scope", body: "The audit covers the year 2026." },
        {
          title: "Engagement letter",
          section: "Related parties",
          body: "Management will provide a complete list of related parties.",
        },
        { title: "Engagement letter", section: "Fees", body: "Fees are billed monthly." },
      ],
    },
  ],
  grounded: true,
  segments: [{ text: "Management will " }, { quote: "q1" }, { text: "." }],
  figures: [],
  tables: [],
  quotes: [quotes.quoted],
};

describe("Sources response", () => {
  it("givenEvidenceWithRows_whenRendered_thenTheResponseIsATableOfThem", () => {
    // Arrange
    const message = withRows();

    // Act
    const html = render(message);

    // Assert
    expect(html).toMatch(/<th[^>]*>Gross salary<\/th>/);
    expect(html).toMatch(/<td[^>]*>Elena Varga<\/td>/);
    expect(html).toMatch(/<td[^>]*>78,000<\/td>/);
    expect(html).not.toContain("&quot;row_count&quot;");
  });

  it("givenTheSelfColumn_whenRendered_thenItIsNotAColumnOfTheTable", () => {
    // Arrange
    const message = withRows();

    // Act
    const html = render(message);

    // Assert
    expect(html).not.toMatch(/<th[^>]*>Self<\/th>/);
    expect(html).not.toMatch(/<td[^>]*>true<\/td>/);
  });

  it("givenAFigureCopiedFromACell_whenRendered_thenThatCellAloneIsMarked", () => {
    // Arrange
    const message = withRows();

    // Act
    const html = render(message);

    // Assert
    expect(html).toMatch(/<td[^>]*text-mark[^>]*>78,000<\/td>/);
    expect(html).not.toMatch(/<td[^>]*text-mark[^>]*>Elena Varga<\/td>/);
  });

  it("givenMoreRowsThanTheAirlockSent_whenRendered_thenSaysHowManyMore", () => {
    // Arrange
    const message = withRows({ row_count: 12 });

    // Act
    const html = render(message);

    // Assert
    expect(text(html)).toContain("and 11 more rows");
  });

  it("givenRows_whenRendered_thenTheRulesAndQueryHashFollowAsOneLine", () => {
    // Arrange
    const message = withRows();

    // Act
    const html = render(message);

    // Assert
    expect(text(html)).toContain(
      "1 row · rules own-record · rule version 1.4c1e0b2a · sql 9f2c41d0",
    );
  });

  it("givenPassages_whenRendered_thenEachShowsItsTitleSectionAndBodyAndTheQuotedOneIsMarked", () => {
    // Arrange
    const message = letter;

    // Act
    const html = render(message);

    // Assert
    expect(text(html)).toContain("Engagement letter · Scope");
    expect(text(html)).toContain("The audit covers the year 2026.");
    expect(html).toMatch(/<p class="[^"]*text-mark[^"]*">Engagement letter · Related parties<\/p>/);
    expect(html).not.toMatch(/<p class="[^"]*text-mark[^"]*">Engagement letter · Scope<\/p>/);
  });

  it("givenEvidenceOfAnOlderAirlockWithoutRows_whenRendered_thenTheResponseIsItsMetaAsBefore", () => {
    // Arrange
    const message = elena;

    // Act
    const html = render(message);

    // Assert
    expect(html).toContain("&quot;row_count&quot;");
    expect(html).not.toContain("<table");
  });
});
