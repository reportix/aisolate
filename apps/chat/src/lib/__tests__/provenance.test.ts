import { describe, expect, it } from "vitest";
import {
  answerMarkdown,
  callLabel,
  callWords,
  citedEvidence,
  computedWords,
  figureCard,
  groundingOf,
  heading,
  numericColumns,
  plainReason,
  sourceRows,
  ownTitle,
} from "../provenance";
import type { Grounding, Message, ToolError } from "../types";
import { markAverage } from "./answers.fixture";
import { elena, figures, quotes, topTwo } from "./grounding.fixture";

describe("groundingOf", () => {
  it("givenAFinalEventWithSegments_whenRead_thenSegmentsFiguresAndProvenanceKept", () => {
    // Arrange
    const event = { type: "done", grounded: true, ...elena };

    // Act
    const grounding = groundingOf(event);

    // Assert
    expect(grounding).toEqual(elena);
  });

  it("givenAFinalEventWithATableAndAQuote_whenRead_thenBothKept", () => {
    // Arrange
    const event = {
      segments: [{ table: "t1" }, { quote: "q1" }],
      figures: [],
      tables: [topTwo],
      quotes: [quotes.quoted],
    };

    // Act
    const grounding = groundingOf(event);

    // Assert
    expect(grounding).toEqual(event);
  });

  it.each([
    ["NoSegments", { figures: elena.figures }],
    ["ASegmentNamingNoFigure", { segments: [{ figure: "f9" }], figures: elena.figures }],
    [
      "AFigureWithAnUnknownStatus",
      { segments: [], figures: [{ id: "f1", display: "1", status: "ok" }] },
    ],
    ["SegmentsThatAreNotAList", { segments: "Your salary is 78,000", figures: [] }],
    ["ASegmentNamingNoTable", { segments: [{ table: "t9" }], figures: [], tables: [topTwo] }],
    [
      "ASegmentNamingNoQuote",
      { segments: [{ quote: "q9" }], figures: [], quotes: [quotes.quoted] },
    ],
    [
      "ATableCellWithAnUnknownStatus",
      {
        segments: [{ table: "t1" }],
        figures: [],
        tables: [{ ...topTwo, rows: [[{ display: "1", status: "ok" }]] }],
      },
    ],
  ])("givenFinalEventWith%s_whenRead_thenNothingKeptSoTheTextRendersAsBefore", (_, event) => {
    // Arrange / Act
    const grounding = groundingOf(event);

    // Assert
    expect(grounding).toEqual({});
  });
});

describe("answerMarkdown", () => {
  it("givenModelTextCarryingAFigureMark_whenJoined_thenOnlyTheAirlocksFiguresAreMarked", () => {
    // Arrange: the model tries to pass its own text off as figure 0
    const grounding = {
      segments: [{ text: "Bonus \uE000f0\uE001, salary " }, { figure: "f1" }],
      figures: [figures.sourced],
    };

    // Act
    const markdown = answerMarkdown(grounding);

    // Assert
    expect(markdown).toBe("Bonus f0, salary \uE000f0\uE001");
  });

  it.each([
    [
      "InQuotationMarks",
      'The letter says: "provide a complete list of related parties".',
      "The letter says: \uE000q0\uE001.",
    ],
    [
      "WithoutQuotationMarks",
      "We provide a complete list of related parties.",
      "We provide a complete list of related parties.",
    ],
  ])("givenAQuotesWords%s_whenJoined_thenOnlyTheQuotedOnesAreItsMark", (_, text, expected) => {
    // Arrange: the airlock writes a placed quote into the text, its words in quotation marks
    const grounding = { segments: [{ text }], figures: [], quotes: [quotes.quoted] };

    // Act
    const markdown = answerMarkdown(grounding);

    // Assert
    expect(markdown).toBe(expected);
  });
});

describe("callWords", () => {
  it.each([
    ["eq", "overdue", "Invoices where status is overdue"],
    ["ne", "paid", "Invoices where status is not paid"],
    ["neq", "paid", "Invoices where status is not paid"],
    ["gt", 1000, "Invoices where status is above 1000"],
    ["gte", 1000, "Invoices where status is at least 1000"],
    ["lt", 1000, "Invoices where status is below 1000"],
    ["lte", 1000, "Invoices where status is at most 1000"],
    ["in", ["open", "overdue"], "Invoices where status is one of open, overdue"],
    ["contains", "due", "Invoices where status contains due"],
    ["between", "x", "Invoices"],
  ])("givenAFilterWithOp %s_whenWorded_thenPlainWords", (op, value, expected) => {
    // Arrange
    const call = {
      tool: "read_records",
      args: { entity: "clients.invoices", filters: [{ column: "status", op, value }] },
    };

    // Act
    const said = callWords(call);

    // Assert
    expect(said).toBe(expected);
  });

  it.each([
    [
      { function: "avg", column: "gross_salary", group_by: "department" },
      "Average gross salary per department",
    ],
    [{ function: "sum", column: "amount_eur" }, "Total amount (EUR)"],
    [{ function: "count", group_by: "client" }, "Number of invoices per client"],
  ])("givenAnAggregate %j_whenWorded_thenItsFunctionAndGroup", (args, expected) => {
    // Arrange
    const call = { tool: "aggregate_records", args: { entity: "clients.invoices", ...args } };

    // Act
    const said = callWords(call);

    // Assert
    expect(said).toBe(expected);
  });
});

describe("computedWords", () => {
  const of = (...displays: string[]) =>
    displays.map((display) => ({
      evidence: "ev-1",
      entity: "clients.invoices",
      column: "amount_eur",
      unit: "EUR",
      display,
    }));

  it.each([
    [
      "sum",
      of("45,000 EUR", "27,500 EUR", "17,500 EUR"),
      "Sum of 3 invoices: 45,000 + 27,500 + 17,500 EUR",
    ],
    ["average", of("30 EUR", "18 EUR"), "Average of 2 invoices: 30, 18 EUR"],
    ["subtract", of("30 EUR", "18 EUR"), "Difference: 30 − 18 EUR"],
    ["divide", of("30 EUR", "4 EUR"), "Ratio: 30 ÷ 4 EUR"],
    [
      "subtract",
      [
        {
          evidence: "ev-1",
          entity: "hr.leave",
          column: "entitlement_days",
          unit: "days",
          display: "30 days",
        },
        {
          evidence: "ev-1",
          entity: "hr.leave",
          column: "taken_days",
          unit: "days",
          display: "12 days",
        },
      ],
      "Difference: entitlement days 30 − taken days 12",
    ],
  ])("givenA %s_whenWorded_thenTheOperationWithItsInputs", (op, operands, expected) => {
    // Arrange
    const computed = { op, of: operands };

    // Act
    const said = computedWords(computed);

    // Assert
    expect(said).toBe(expected);
  });

  it("givenARanking_whenWorded_thenItsOrderAndColumn", () => {
    // Arrange
    const computed = {
      op: "top_n",
      n: 3,
      by: { evidence: "ev-3", entity: "clients.accounts", column: "contract_value_eur" },
    };

    // Act
    const said = computedWords(computed);

    // Assert
    expect(said).toBe("Top 3 accounts by contract value (EUR)");
  });
});

describe("plainReason", () => {
  const ctx = {
    call: () => undefined,
    number: (id: string) => (id === "ev-2" ? 1 : id === "ev-3" ? 2 : undefined),
  };

  it.each([
    [
      "the row is about Eric Lindholm, not Leo Brandt",
      "the row is about Eric Lindholm, not Leo Brandt",
    ],
    ["ev-3 (hr.leave) has no column gross_salary", "leave has no column gross salary"],
    [
      "column iban of ev-2 is masked: it cannot be cited",
      "column iban of source 1 is masked: it cannot be cited",
    ],
    ["there is no evidence ev-9 in this exchange", "it points to data this answer never read"],
    ["r5 is a row of ev-3, not of ev-2", "the row is from source 2, not source 1"],
    [
      "the answer places {f2} but gives no reference for it",
      "the answer places a figure but gives no reference for it",
    ],
    [
      "the rows are out of order: Leo Brandt (r7) ranks before Nora Varga (r5)",
      "the rows are out of order: Leo Brandt ranks before Nora Varga",
    ],
  ])("givenTheReason %s_whenMadePlain_thenNoIdIsLeft", (reason, expected) => {
    // Arrange / Act
    const plain = plainReason(reason, ctx);

    // Assert
    expect(plain).toBe(expected);
  });
});

describe("figureCard", () => {
  const ctx = { call: () => undefined, number: () => undefined };

  it.each([
    [undefined, "The model typed this instead of citing it."],
    ["typed in the text, not referenced", "The model typed this instead of citing it."],
    [
      "typed in the text, not referenced; ev-3 r4 drug holds the same value",
      "The model typed this instead of citing it. The records hold the same value.",
    ],
    [
      "typed in the text, not referenced; ev-4 r8 value holds the same value",
      "The model typed this instead of citing it. The records hold the same value.",
    ],
  ])(
    "givenAnUnsourcedFigureWithTheReason %s_whenCarded_thenItSaysTypedAndNeverAnId",
    (reason, expected) => {
      // Arrange
      const figure = { display: "Apixaban", status: "unsourced" as const, reason };

      // Act
      const card = figureCard(figure, ctx);

      // Assert
      expect(card).toBe(expected);
    },
  );
});

describe("ownTitle", () => {
  it.each([
    [
      "Employment Contract John Doe (the asking user's own)",
      "John Doe",
      "Your employment contract",
    ],
    [
      "Employment Contract John Doe (the asking user's own)",
      undefined,
      "Your employment Contract John Doe",
    ],
    ["Employment Contract Jane Doe", "John Doe", "Employment Contract Jane Doe"],
  ])("givenTitle%s_whenOwnedBy%s_thenReadsAs%s", (title, user, expected) => {
    // Arrange

    // Act
    const read = ownTitle(title, user);

    // Assert
    expect(read).toBe(expected);
  });
});

describe("sourceRows", () => {
  it("givenCitedEvidenceAndAnUncitedCall_whenNumbered_thenCitedFirstThenTheRest", () => {
    // Arrange
    const [, message] = markAverage;
    const markdown = answerMarkdown(message as Grounding);

    // Act
    const rows = sourceRows(message, citedEvidence(markdown, message as Grounding));

    // Assert
    expect(rows.map((r) => [r.label, r.detail])).toEqual([
      ["Average gross salary per department where department is Sales", "1 row"],
      ["Your compensation", ""],
    ]);
  });

  it("givenOneFilterOnAPersonsName_whenListed_thenTheRowIsTheirs", () => {
    // Arrange: a read of someone else's row, which the rules let through empty
    const call = {
      id: "c1",
      tool: "read_records",
      args: { filters: [{ column: "employee", op: "ilike", value: "Jane Doe" }] },
      done: true,
    };
    const evidence = {
      id: "ev-1",
      call_id: "c1",
      tool: "read_records",
      entity: "hr.contract_terms",
      row_count: 0,
      columns: ["employee"],
      rule_version: "",
      rules: [],
      sql_hash: "",
    };
    const message = { ...markAverage[1], toolCalls: [call], evidence: [evidence] };

    // Act
    const [row] = sourceRows(message, []);

    // Assert
    expect([row.label, row.detail, row.none]).toEqual([
      "Jane Doe's contract terms",
      "nothing you may read",
      true,
    ]);
  });

  it("givenACallStillRunning_whenListed_thenItSaysSo", () => {
    // Arrange
    const message = { ...markAverage[1], status: "streaming" as const, evidence: [] };

    // Act
    const rows = sourceRows(message, []);

    // Assert
    expect(rows.map((r) => [r.detail, r.running])).toEqual([
      ["", true],
      ["", true],
    ]);
  });

  it.each([
    ["login_refused", "the login was refused"],
    ["unreachable", "could not be reached"],
    ["failed", "did not complete"],
    ["a_category_this_build_does_not_know", "did not complete"],
  ])("givenACallThatFailedWith_%s_whenListed_thenItIsMarkedFailed_%s", (error, why) => {
    // Arrange
    const message: Message = {
      id: "a1",
      role: "assistant",
      content: "I couldn't reach the data to answer this.",
      evidence: [],
      status: "done",
      toolCalls: [
        {
          id: "call-1",
          tool: "read_records",
          args: {
            entity: "hr.compensation",
            filters: [{ column: "full_name", op: "ilike", value: "elena" }],
          },
          done: true,
          error: error as ToolError,
        },
      ],
    };

    // Act
    const rows = sourceRows(message, []);

    // Assert
    expect(rows.map((r) => [r.label, r.detail, r.error])).toEqual([
      ["Compensation where full name contains elena", "", why],
    ]);
  });
});

describe("callLabel", () => {
  it.each([
    ["NoArguments", {}, "read_my_records()"],
    [
      "TextAndNumbers",
      { order: "income desc", limit: 5 },
      "read_my_records(order: income desc, limit: 5)",
    ],
    [
      "AFilterList",
      { filters: [{ column: "amount", op: "gt", value: 1000 }] },
      'read_my_records(filters: [{"column":"amount","op":"gt","value":1000}])',
    ],
  ])("given%s_whenShown_thenTheToolAndEachArgument", (_, args, expected) => {
    // Arrange
    const call = { tool: "read_my_records", args };

    // Act
    const label = callLabel(call);

    // Assert
    expect(label).toBe(expected);
  });
});

describe("heading", () => {
  it.each([
    ["gross_salary", "Gross salary"],
    ["income", "Income"],
    ["amount_eur", "Amount (EUR)"],
  ])("givenColumn%s_whenAHead_thenSentenceCase", (column, expected) => {
    // Arrange / Act
    const head = heading(column);

    // Assert
    expect(head).toBe(expected);
  });
});

describe("numericColumns", () => {
  it("givenATableOfNamesAndAmounts_whenAligned_thenOnlyTheAmountsAreNumeric", () => {
    // Arrange
    const table = topTwo;

    // Act
    const numeric = numericColumns(table);

    // Assert
    expect(numeric).toEqual([false, true]);
  });
});
