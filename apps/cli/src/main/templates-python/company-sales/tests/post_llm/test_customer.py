import json

import pytest
from aisolate import Answer, Evidence, Status
from support import FakeAIsolateManager

from acme_sales.post_llm.customer import CustomerVerifier

NORTHWIND, CONTOSO, FABRIKAM, TAILSPIN = "Northwind Bank AG", "Contoso Retail", "Fabrikam Logistics", "Tailspin Air"


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
PASSAGES = Evidence(
    "ev-1",
    "SalesDocumentsContextPreparator",
    json.dumps([{"title": f"Master agreement {NORTHWIND}", "section": "Pricing", "body": "Increases are capped."}]),
)
INVOICES = Evidence(
    "ev-2",
    "read_records",
    tool_result(
        "clients.invoices",
        [
            {"invoice_id": 412, "client": NORTHWIND, "number": "INV-2026-0412", "amount_eur": 18400},
            {"invoice_id": 433, "client": NORTHWIND, "number": "INV-2026-0433", "amount_eur": 6250},
            {"invoice_id": 388, "client": FABRIKAM, "number": "INV-2026-0388", "amount_eur": 12900},
        ],
        row_key="invoice_id",
        subject="client",
        units={"amount_eur": "EUR"},
    ),
)
ACCOUNTS = Evidence(
    "ev-3",
    "read_records",
    tool_result(
        "clients.accounts",
        [
            {"account_id": 1, "name": NORTHWIND, "contract_value_eur": 240000},
            {"account_id": 2, "name": CONTOSO, "contract_value_eur": 112800},
            {"account_id": 3, "name": FABRIKAM, "contract_value_eur": 98000},
            {"account_id": 4, "name": TAILSPIN, "contract_value_eur": 310000},
        ],
        row_key="account_id",
        subject="name",
        units={"contract_value_eur": "EUR"},
    ),
)
TOTALS = Evidence(
    "ev-4",
    "aggregate_records",
    tool_result(
        "clients.invoices",
        [{"client": NORTHWIND, "value": 24650, "n": 2}, {"client": FABRIKAM, "value": 12900, "n": 1}],
        row_key="client",
        subject="client",
        units={"value": "EUR"},
        function="sum",
    ),
)
# A tool the project wrote itself builds its result without labels.
OWN_TOOL = Evidence(
    "ev-5",
    "find_notes",
    json.dumps({"entity": "notes", "row_count": 1, "columns": ["note"], "rows": [{"note": "Call"}], "labels": None}),
)
EVIDENCE = [PASSAGES, INVOICES, ACCOUNTS, TOTALS, OWN_TOOL]


def source(evidence, entity, column, row_key, subject, unit=None):
    """Where a cited value is from, as the airlock writes it: what is null is left out."""
    fields = {"evidence": evidence, "entity": entity, "column": column, "row_key": row_key, "subject": subject}
    return {key: value for key, value in {**fields, "unit": unit}.items() if value is not None}


def number(invoice_id, client):
    return source("ev-2", "clients.invoices", "number", invoice_id, client)


def amount(invoice_id, client):
    return source("ev-2", "clients.invoices", "amount_eur", invoice_id, client, "EUR")


def contract_value(account_id, name):
    return source("ev-3", "clients.accounts", "contract_value_eur", account_id, name, "EUR")


def operand(display, cited):
    """One cell of a computation, shown as itself."""
    return {**cited, "display": display}


def cell(display, cited, status="sourced"):
    return {"display": display, "status": status, "source": cited}


def grounded(figures=(), tables=()):
    """The members of a grounded answer that a verifier reads."""
    return json.dumps({"figures": list(figures), "tables": list(tables)})


def grounding(display, cited, status="sourced"):
    return grounded([{"id": "f1", "display": display, "status": status, "source": cited}])


def grounding_computed(display, **computed):
    return grounded([{"id": "f1", "display": display, "status": "computed", "computed": computed}])


def grounding_table(rows):
    return grounded(tables=[{"id": "t1", "status": "sourced", "rows": rows}])


def test_given_northwinds_overdue_invoices_as_table_when_verified_then_pass():
    # Arrange
    table = grounding_table(
        [
            [cell("INV-2026-0412", number("412", NORTHWIND)), cell("18,400 EUR", amount("412", NORTHWIND))],
            [cell("INV-2026-0433", number("433", NORTHWIND)), cell("6,250 EUR", amount("433", NORTHWIND))],
        ]
    )
    answer = Answer("Two invoices of Northwind are overdue.", (), "Which invoices of Northwind are overdue?", table)

    # Act
    verdict = CustomerVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every figure is about Northwind Bank AG")


def test_given_question_naming_no_customer_when_verified_then_pass():
    # Arrange
    figure = grounding("18,400 EUR", amount("412", NORTHWIND))
    answer = Answer("18,400 EUR is overdue.", (), "Which invoices are overdue?", figure)

    # Act
    verdict = CustomerVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the question names no customer")


def test_given_no_grounding_when_verified_then_pass():
    # Arrange
    answer = Answer("I don't know", (), "What does Fabrikam owe?", "")

    # Act
    verdict = CustomerVerifier().verify(answer, [], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


def test_given_comparison_of_two_named_customers_when_verified_then_pass_naming_both_sorted():
    # Arrange
    smaller, larger = contract_value("3", FABRIKAM), contract_value("1", NORTHWIND)
    percent = grounding_computed(
        "144.9%", op="percent_change", of=[operand("98,000 EUR", smaller), operand("240,000 EUR", larger)]
    )
    question = "How much larger is Northwind's contract than Fabrikam's?"
    answer = Answer("Northwind's contract is 144.9% larger.", (), question, percent)

    # Act
    verdict = CustomerVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.PASS,
        "every figure is about Fabrikam Logistics, Northwind Bank AG",
    )


@pytest.mark.parametrize(
    "question, text, grounding_json, note",
    [
        pytest.param(
            "What does Fabrikam owe?",
            "Fabrikam owes 18,400 EUR.",
            grounding("18,400 EUR", amount("412", NORTHWIND)),
            "the figure 18,400 EUR is about Northwind Bank AG; the question asks about Fabrikam Logistics",
            id="invoice",
        ),
        pytest.param(
            "What does Northwind owe in total?",
            "Northwind owes 12,900 EUR.",
            grounding("12,900 EUR", source("ev-4", "clients.invoices", "value", FABRIKAM, FABRIKAM, "EUR")),
            "the figure 12,900 EUR is about Fabrikam Logistics; the question asks about Northwind Bank AG",
            id="total-of-an-aggregate",
        ),
        pytest.param(
            "What does Northwind owe in total?",
            "Northwind owes 31,300 EUR.",
            grounding_computed(
                "31,300 EUR",
                op="sum",
                of=[operand("18,400 EUR", amount("412", NORTHWIND)), operand("12,900 EUR", amount("388", FABRIKAM))],
            ),
            "the figure 12,900 EUR is about Fabrikam Logistics; the question asks about Northwind Bank AG",
            id="operand-shown-as-itself",
        ),
        pytest.param(
            "What are Northwind's two biggest invoices?",
            "Northwind's two biggest invoices: Northwind Bank AG, Fabrikam Logistics.",
            grounding_computed(
                "Northwind Bank AG, Fabrikam Logistics",
                op="top_n",
                n=2,
                by={"evidence": "ev-2", "entity": "clients.invoices", "column": "amount_eur"},
                rows=[operand("18,400 EUR", amount("412", NORTHWIND)), operand("12,900 EUR", amount("388", FABRIKAM))],
            ),
            "the figure 12,900 EUR is about Fabrikam Logistics; the question asks about Northwind Bank AG",
            id="ranked-row-shown-as-itself",
        ),
        pytest.param(
            "What is Fabrikam's contract worth?",
            "Fabrikam's contract is worth 310,000 EUR.",
            grounding_computed(
                "310,000 EUR",
                op="max",
                by={"evidence": "ev-3", "entity": "clients.accounts", "column": "contract_value_eur"},
                row=contract_value("4", TAILSPIN),
            ),
            "the figure 310,000 EUR is about Tailspin Air; the question asks about Fabrikam Logistics",
            id="row-shown-as-the-figure",
        ),
        pytest.param(
            "Which invoices of Northwind are overdue?",
            "Two invoices of Northwind are overdue.",
            grounding_table(
                [[cell("18,400 EUR", amount("412", NORTHWIND))], [cell("12,900 EUR", amount("388", FABRIKAM))]]
            ),
            "the figure 12,900 EUR is about Fabrikam Logistics; the question asks about Northwind Bank AG",
            id="table-cell",
        ),
    ],
)
def test_given_another_customers_figure_or_cell_when_verified_then_fail(question, text, grounding_json, note):
    # Arrange
    answer = Answer(text, (), question, grounding_json)

    # Act
    verdict = CustomerVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, note)


@pytest.mark.parametrize(
    "question, text, grounding_json",
    [
        pytest.param(
            "What did we agree with Northwind on the price cap?",
            "The agreement with Northwind Bank AG says: Price increases are capped.",
            grounded(),
            id="quote-only-answer",
        ),
        pytest.param("What does Fabrikam owe?", "I don't know", grounded(), id="dont-know"),
        pytest.param(
            "What does Northwind owe in total?",
            "Northwind owes 24,650 EUR.",
            grounding("24,650 EUR", source("ev-4", "clients.invoices", "value", None, None, "EUR")),
            id="source-without-subject",
        ),
    ],
)
def test_given_no_cited_source_with_a_subject_when_verified_then_pass_with_no_figure_to_check(
    question, text, grounding_json
):
    # Arrange
    answer = Answer(text, (), question, grounding_json)

    # Act
    verdict = CustomerVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


def test_given_a_figure_grounding_flagged_from_another_customers_row_when_verified_then_fail():
    # Arrange
    figure = grounding("18,400 EUR", amount("412", NORTHWIND), "mismatch")
    answer = Answer("Fabrikam owes 18,400 EUR.", (), "What does Fabrikam owe?", figure)

    # Act
    verdict = CustomerVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the figure 18,400 EUR is about Northwind Bank AG; the question asks about Fabrikam Logistics",
    )


@pytest.mark.parametrize("without", [{}, {"rows": None}], ids=["absent", "null"])
def test_given_a_tool_result_without_rows_when_verified_then_it_names_no_customer(without):
    # Arrange
    result = {"entity": "policies", "row_count": 0, "columns": [], "labels": {"subject": "title"}, **without}
    evidence = [Evidence("ev-6", "find_policies", json.dumps(result)), INVOICES]
    figure = grounding("12,900 EUR", amount("388", FABRIKAM))
    answer = Answer("Fabrikam owes 12,900 EUR.", (), "What does Fabrikam owe?", figure)

    # Act
    verdict = CustomerVerifier().verify(answer, evidence, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every figure is about Fabrikam Logistics")


@pytest.mark.parametrize(
    "customer, question",
    [
        pytest.param("Air Nordic AG", "Which invoices are fairly late?", id="word-that-only-contains-the-name"),
        pytest.param("AG Holdings", "Which invoices does AG owe?", id="leading-word-of-under-three-letters"),
    ],
)
def test_given_question_that_only_resembles_a_customers_name_when_verified_then_it_names_none(customer, question):
    # Arrange
    rows = [{"account_id": 7, "name": customer}]
    evidence = [*EVIDENCE, Evidence("ev-6", "read_records", tool_result("clients.accounts", rows, subject="name"))]
    answer = Answer("18,400 EUR is overdue.", (), question, grounding("18,400 EUR", amount("412", NORTHWIND)))

    # Act
    verdict = CustomerVerifier().verify(answer, evidence, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the question names no customer")
