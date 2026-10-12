import json

import pytest
from aisolate import Answer, Evidence, Status
from support import FakeAIsolateManager

from halvardfinch_audit.post_llm.period import PeriodVerifier

HARBOUR, FINCH = "Harbour Lane Properties GmbH", "Finch Advisory UG"
REVENUE, PROFIT = "ifrs-full:Revenue", "ifrs-full:ProfitLoss"
QUESTION_2025 = "What revenue did Northbeck report for 2025?"


def tool_result(entity, rows, **labels):
    """A tool result as the tool server writes it; ``labels`` are the ones the entity declares."""
    unset = {"row_key": None, "subject": None, "period": None, "units": {}, "unit_columns": {}, "decimals": {}}
    return json.dumps(
        {
            "entity": entity,
            "row_count": len(rows),
            "columns": list(rows[0]),
            "rows": rows,
            "labels": {**unset, "masked": [], "function": None, **labels},
        }
    )


# A passages evidence is a JSON list, not a tool result.
POLICIES = Evidence(
    "ev-1",
    "AuditDocumentsContextPreparator",
    json.dumps(
        [{"title": "Related party policy", "section": "Approval", "body": "Payments above 10,000 EUR need approval."}]
    ),
)
PAYMENTS = Evidence(
    "ev-2",
    "related_party_payments",
    tool_result(
        "ledger.journal_entries",
        [
            {"entry_id": 1, "entry_date": "2025-10-03", "counterparty": HARBOUR, "amount_eur": 48500},
            {"entry_id": 7, "entry_date": "2025-11-20", "counterparty": FINCH, "amount_eur": 22000},
            {"entry_id": 12, "entry_date": "2026-01-15", "counterparty": HARBOUR, "amount_eur": 48500},
        ],
        row_key="entry_id",
        subject="counterparty",
        period="entry_date",
        units={"amount_eur": "EUR"},
    ),
)
REPORTED = Evidence(
    "ev-3",
    "read_records",
    tool_result(
        "ledger.reported_figures",
        [
            {"figure_id": f"{REVENUE} 2025", "concept": REVENUE, "period": "2025", "value_eur": 12400000},
            {"figure_id": f"{REVENUE} 2024", "concept": REVENUE, "period": "2024", "value_eur": 11100000},
            {"figure_id": f"{REVENUE} 2023", "concept": REVENUE, "period": "2023", "value_eur": 10200000},
            {"figure_id": f"{PROFIT} 2025", "concept": PROFIT, "period": "2025", "value_eur": 860000},
        ],
        row_key="figure_id",
        period="period",
        units={"value_eur": "EUR"},
    ),
)
EVIDENCE = [POLICIES, PAYMENTS, REPORTED]


def source(evidence, entity, column, row_key, subject, period, unit):
    """Where a cited value is from, as the airlock writes it: what is null is left out."""
    fields = {
        "evidence": evidence,
        "entity": entity,
        "column": column,
        "row_key": row_key,
        "subject": subject,
        "period": period,
        "unit": unit,
    }
    return {key: value for key, value in fields.items() if value is not None}


def payment(entry_id, counterparty, entry_date):
    return source("ev-2", "ledger.journal_entries", "amount_eur", entry_id, counterparty, entry_date, "EUR")


def reported(concept, period):
    """The value of a concept for a period: its figure_id is the two together."""
    return source("ev-3", "ledger.reported_figures", "value_eur", f"{concept} {period}", None, period, "EUR")


RENT_Q4_2025 = payment("1", HARBOUR, "2025-10-03")
ADVISORY_2025 = payment("7", FINCH, "2025-11-20")
RENT_Q1_2026 = payment("12", HARBOUR, "2026-01-15")


def operand(display, cited):
    """One cell of a computation, shown as itself."""
    return {**cited, "display": display}


CELL_2024 = operand("11,100,000 EUR", reported(REVENUE, "2024"))
CELL_2025 = operand("12,400,000 EUR", reported(REVENUE, "2025"))


def cell(display, cited, status="sourced"):
    return {"display": display, "status": status, "source": cited}


def grounded(figures=(), tables=()):
    """The members of a grounded answer that a verifier reads."""
    return json.dumps({"figures": list(figures), "tables": list(tables)})


def sourced(figure_id, display, cited, status="sourced"):
    return {"id": figure_id, "display": display, "status": status, "source": cited}


def computed_figure(figure_id, display, **computed):
    return {"id": figure_id, "display": display, "status": "computed", "computed": computed}


def grounding(display, cited, status="sourced"):
    return grounded([sourced("f1", display, cited, status)])


def grounding_computed(display, **computed):
    return grounded([computed_figure("f1", display, **computed)])


def grounding_table(rows):
    return grounded(tables=[{"id": "t1", "status": "sourced", "rows": rows}])


@pytest.mark.parametrize("period", ["2024", "FY2024"])
def test_given_revenue_of_another_year_when_verified_then_fail(period):
    # Arrange
    figure = grounding("11,100,000 EUR", reported(REVENUE, period))
    answer = Answer("Northbeck reported 11,100,000 EUR.", (), QUESTION_2025, figure)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        f"the figure 11,100,000 EUR is for {period}; the question asks about 2025",
    )


@pytest.mark.parametrize(
    "question, display, cited",
    [
        pytest.param(QUESTION_2025, "12,400,000 EUR", reported(REVENUE, "2025"), id="year"),
        pytest.param(QUESTION_2025, "12,400,000 EUR", reported(REVENUE, "FY2025"), id="fiscal-year"),
        pytest.param("How much was paid to Harbour Lane in 2025?", "48,500 EUR", RENT_Q4_2025, id="date"),
        pytest.param("How much was paid to Harbour Lane on 2025-10-03?", "48,500 EUR", RENT_Q4_2025, id="date-asked"),
        pytest.param("What was paid in 2025, and on 2025-10-03?", "48,500 EUR", RENT_Q4_2025, id="year-named-twice"),
        pytest.param("What was paid in \u06622025?", "48,500 EUR", RENT_Q4_2025, id="year-after-a-non-ascii-digit"),
        pytest.param(
            "What was paid in 2025 European sales?", "48,500 EUR", RENT_Q4_2025, id="year-before-a-word-of-eur"
        ),
        pytest.param(
            "What was paid in 2025 USDA fees?", "48,500 EUR", RENT_Q4_2025, id="year-before-a-word-of-usd"
        ),
    ],
)
def test_given_a_figure_of_the_year_asked_when_verified_then_pass(question, display, cited):
    # Arrange
    answer = Answer(f"{display}.", (), question, grounding(display, cited))

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every figure is for 2025")


@pytest.mark.parametrize(
    "question",
    [
        pytest.param("What was paid to Harbour Lane?", id="no-number"),
        pytest.param("Which payments are for invoice 12025?", id="digit-before"),
        pytest.param("Which payments are for invoice 20255?", id="digit-after"),
        pytest.param("What was paid in 2100?", id="other-century"),
        pytest.param("What was paid in 20\u0662\u0665?", id="non-ascii-digits"),
        pytest.param("Which related-party payments exceeded 2000 EUR?", id="eur"),
        pytest.param("Which related-party payments exceeded 2000 eur?", id="lower-case"),
        pytest.param("Which related-party payments exceeded 2000 USD?", id="usd"),
        pytest.param("Which related-party payments exceeded 2000 \u20ac?", id="euro-sign-after"),
        pytest.param("Which related-party payments exceeded \u20ac2000?", id="euro-sign-before"),
        pytest.param("Which related-party payments exceeded $2000?", id="dollar-before"),
        pytest.param("Which related-party payments exceeded 2000$?", id="dollar-after"),
        pytest.param("Which related-party payments grew by more than 2000%?", id="percent"),
    ],
)
def test_given_digits_that_are_no_year_in_the_question_when_verified_then_it_names_no_period(question):
    # Arrange: a 2025 figure, which a question read as asking about 2000 or 2026 would fail
    figure = grounding("48,500 EUR", RENT_Q4_2025)
    answer = Answer("48,500 EUR was paid to Harbour Lane.", (), question, figure)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the question names no period")


@pytest.mark.parametrize(
    "question",
    [
        pytest.param("Which payments above \u20ac2025 were made in 2025?", id="amount-and-year-alike"),
        pytest.param("Which payments above 2000 EUR were made in 2025?", id="amount-and-year-apart"),
    ],
)
def test_given_an_amount_and_a_year_in_the_question_when_verified_then_only_the_year_is_asked(question):
    # Arrange
    figure = grounding("48,500 EUR", RENT_Q1_2026)
    answer = Answer("48,500 EUR was paid to Harbour Lane.", (), question, figure)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the figure 48,500 EUR is for 2026-01-15; the question asks about 2025",
    )


def test_given_table_of_payments_in_the_year_asked_when_verified_then_pass():
    # Arrange
    table = grounding_table([[cell("48,500 EUR", RENT_Q4_2025)], [cell("22,000 EUR", ADVISORY_2025)]])
    answer = Answer("Two payments to related parties were made in 2025.", (), "Payments in 2025?", table)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every figure is for 2025")


@pytest.mark.parametrize(
    "question",
    [
        pytest.param("Payments in 2025?", id="question-names-a-year"),
        pytest.param("What was paid to Harbour Lane?", id="question-names-no-year"),
    ],
)
def test_given_no_grounding_when_verified_then_pass_with_no_figure_to_check(question):
    # Arrange
    answer = Answer("I don't know", (), question, "")

    # Act
    verdict = PeriodVerifier().verify(answer, [], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


def test_given_figures_of_the_two_years_asked_when_verified_then_pass_naming_both_sorted():
    # Arrange
    figures = grounded(
        [
            sourced("f1", "12,400,000 EUR", reported(REVENUE, "2025")),
            sourced("f2", "11,100,000 EUR", reported(REVENUE, "2024")),
        ]
    )
    question = "What revenue did Northbeck report for 2025 and 2024?"
    answer = Answer("Northbeck reported 12,400,000 EUR and 11,100,000 EUR.", (), question, figures)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every figure is for 2024, 2025")


@pytest.mark.parametrize(
    "op, display, of",
    [
        pytest.param("percent_change", "11.71%", [CELL_2024, CELL_2025], id="growth"),
        pytest.param("subtract", "1,300,000 EUR", [CELL_2025, CELL_2024], id="difference"),
    ],
)
def test_given_a_computation_across_periods_when_verified_then_its_operands_are_not_judged(op, display, of):
    # Arrange
    computed = grounding_computed(display, op=op, of=of)
    answer = Answer(f"Revenue grew by {display} in 2025.", (), "How much did revenue grow in 2025?", computed)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


def test_given_growth_with_the_cells_it_is_made_of_when_verified_then_pass_naming_the_year_asked():
    # Arrange
    figures = grounded(
        [
            sourced("f1", "11,100,000 EUR", reported(REVENUE, "2024")),
            sourced("f2", "12,400,000 EUR", reported(REVENUE, "2025")),
            computed_figure("f3", "11.71%", op="percent_change", of=[CELL_2024, CELL_2025]),
        ]
    )
    text = "Revenue grew from 11,100,000 EUR to 12,400,000 EUR, a rise of 11.71%."
    answer = Answer(text, (), "How much did revenue grow in 2025?", figures)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every figure is for 2025")


def test_given_growth_beside_a_figure_of_a_year_it_does_not_span_when_verified_then_fail():
    # Arrange
    figures = grounded(
        [
            sourced("f1", "10,200,000 EUR", reported(REVENUE, "2023")),
            computed_figure("f2", "11.71%", op="percent_change", of=[CELL_2024, CELL_2025]),
        ]
    )
    answer = Answer("Revenue grew from 10,200,000 EUR, by 11.71%.", (), "How much did revenue grow in 2025?", figures)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the figure 10,200,000 EUR is for 2023; the question asks about 2025",
    )


@pytest.mark.parametrize("cited_evidence", ["ev-1", "ev-9"], ids=["a-document", "not-in-the-exchange"])
def test_given_growth_with_a_cell_citing_no_tool_result_when_verified_then_its_year_is_not_expected(cited_evidence):
    # Arrange
    cells = [{**CELL_2024, "evidence": cited_evidence}, CELL_2025]
    figures = grounded(
        [
            sourced("f1", "11,100,000 EUR", reported(REVENUE, "2024")),
            computed_figure("f2", "11.71%", op="percent_change", of=cells),
        ]
    )
    answer = Answer("Revenue grew from 11,100,000 EUR by 11.71%.", (), "How much did revenue grow in 2025?", figures)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the figure 11,100,000 EUR is for 2024; the question asks about 2025",
    )


@pytest.mark.parametrize(
    "question, text, grounding_json, note",
    [
        pytest.param(
            "What are the two largest figures reported for 2025?",
            "The two largest figures reported for 2025 are 12,400,000 EUR and 11,100,000 EUR.",
            grounding_computed(
                "12,400,000 EUR, 11,100,000 EUR",
                op="top_n",
                n=2,
                by={"evidence": "ev-3", "entity": "ledger.reported_figures", "column": "value_eur"},
                rows=[
                    operand("12,400,000 EUR", reported(REVENUE, "2025")),
                    operand("11,100,000 EUR", reported(REVENUE, "2024")),
                ],
            ),
            "the figure 11,100,000 EUR is for 2024; the question asks about 2025",
            id="ranked-row-shown-as-itself",
        ),
        pytest.param(
            "What is the smallest figure reported for 2024?",
            "The smallest figure reported for 2024 is 860,000 EUR.",
            grounding_computed(
                "860,000 EUR",
                op="min",
                by={"evidence": "ev-3", "entity": "ledger.reported_figures", "column": "value_eur"},
                row=reported(PROFIT, "2025"),
            ),
            "the figure 860,000 EUR is for 2025; the question asks about 2024",
            id="row-shown-as-the-figure",
        ),
        pytest.param(
            "Payments in 2025?",
            "Three payments to related parties were made.",
            grounding_table(
                [
                    [cell("48,500 EUR", RENT_Q4_2025)],
                    [cell("22,000 EUR", ADVISORY_2025)],
                    [cell("48,500 EUR", RENT_Q1_2026)],
                ]
            ),
            "the figure 48,500 EUR is for 2026-01-15; the question asks about 2025",
            id="table-cell",
        ),
        pytest.param(
            "What was paid to Harbour Lane in 2025 and 2024?",
            "48,500 EUR was paid to Harbour Lane.",
            grounding("48,500 EUR", RENT_Q1_2026),
            "the figure 48,500 EUR is for 2026-01-15; the question asks about 2024, 2025",
            id="two-years-asked",
        ),
    ],
)
def test_given_a_cited_cell_of_another_year_when_verified_then_fail(question, text, grounding_json, note):
    # Arrange
    answer = Answer(text, (), question, grounding_json)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, note)


def test_given_a_figure_grounding_flagged_from_another_year_when_verified_then_fail():
    # Arrange
    figure = grounding("11,100,000 EUR", reported(REVENUE, "2024"), "mismatch")
    answer = Answer("Northbeck reported 11,100,000 EUR.", (), QUESTION_2025, figure)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the figure 11,100,000 EUR is for 2024; the question asks about 2025",
    )


@pytest.mark.parametrize(
    "grounding_json",
    [
        pytest.param(grounded(), id="no-figure"),
        pytest.param(grounded([{"id": "u1", "display": "12", "status": "unsourced"}]), id="unsourced-figure"),
        pytest.param(
            grounding(
                "12,400,000 EUR", source("ev-3", "ledger.reported_figures", "value_eur", REVENUE, None, None, "EUR")
            ),
            id="source-without-period",
        ),
        pytest.param(grounding("12,400,000 EUR", reported(REVENUE, "Q3")), id="period-without-year"),
    ],
)
def test_given_no_cited_source_with_a_year_when_verified_then_pass_with_no_figure_to_check(grounding_json):
    # Arrange
    answer = Answer("Nothing to say.", (), QUESTION_2025, grounding_json)

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


@pytest.mark.parametrize("cited_evidence", ["ev-1", "ev-9"], ids=["a-document", "not-in-the-exchange"])
def test_given_a_source_citing_no_tool_result_when_verified_then_it_is_not_counted(cited_evidence):
    # Arrange
    cited = {**reported(REVENUE, "2024"), "evidence": cited_evidence}
    answer = Answer("Northbeck reported 11,100,000 EUR.", (), QUESTION_2025, grounding("11,100,000 EUR", cited))

    # Act
    verdict = PeriodVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")
