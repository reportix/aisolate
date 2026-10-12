import pytest
from aisolate import Filter, Fn, Op
from support import RecordingQuery

from halvardfinch_audit.tools.ledger_tools import INVOICES, JOURNAL_ENTRIES, LedgerTools, related_party_payment_filters


@pytest.mark.parametrize("name", ["read_records", "aggregate_records"])
def test_given_entity_of_no_plugin_when_called_then_refused_naming_the_choices_without_a_query(name):
    # Arrange: another company's books are another airlock's data source, never an entity here
    query = RecordingQuery()
    args = {"entity": "northbeck.invoices", "function": "sum", "column": "amount_eur"}

    # Act
    with pytest.raises(ValueError) as thrown:
        tool(name).handler(query, args)

    # Assert
    assert str(thrown.value) == (
        "unknown entity northbeck.invoices; one of [ledger.journal_entries, ledger.invoices,"
        " ledger.contracts, ledger.related_parties, ledger.reported_figures]"
    )
    assert query.calls == []


@pytest.mark.parametrize("limit", [5, 500])
def test_given_any_limit_when_read_records_then_every_row_up_to_50(limit):
    # Arrange: a limit of 5 asked for "the five largest" read the five oldest, so none is offered
    query = RecordingQuery()
    args = {"entity": "ledger.contracts", "limit": limit}

    # Act
    tool("read_records").handler(query, args)

    # Assert
    assert query.calls == [("read", "ledger.contracts", [], 50)]
    assert "limit" not in str(tool("read_records").input_schema)


def test_given_group_by_when_aggregate_records_then_one_query_grouped_by_it():
    # Arrange
    query = RecordingQuery()
    args = {"entity": "ledger.invoices", "function": "sum", "column": "amount_eur", "group_by": "supplier"}

    # Act
    tool("aggregate_records").handler(query, args)

    # Assert
    assert query.calls == [("aggregate", "ledger.invoices", Fn.SUM, "amount_eur", "supplier", [])]


@pytest.mark.parametrize(("threshold", "expected"), [(None, 10000), (25000, 25000)])
def test_given_threshold_when_related_party_payments_then_payments_to_related_parties_above_it(threshold, expected):
    # Arrange
    query = RecordingQuery()
    args = {"min_amount_eur": threshold}

    # Act
    tool("related_party_payments").handler(query, args)

    # Assert
    assert query.calls == [
        (
            "read",
            "ledger.journal_entries",
            [
                Filter("related_party", Op.EQ, "yes"),
                Filter("entry_type", Op.EQ, "payment"),
                Filter("amount_eur", Op.GT, expected),
            ],
            50,
        )
    ]


def test_given_no_arguments_when_invoices_without_contract_then_invoices_with_no_matching_contract():
    # Arrange
    query = RecordingQuery()
    args = {}

    # Act
    tool("invoices_without_contract").handler(query, args)

    # Assert
    assert query.calls == [("read", "ledger.invoices", [Filter("has_contract", Op.EQ, "no")], 50)]


def test_given_the_audit_tests_when_their_filters_then_each_column_is_one_of_the_entity():
    # Arrange: the tool server refuses a filter on a column the entity does not declare
    filters = related_party_payment_filters(None)

    # Act
    columns = [f.column for f in filters]

    # Assert
    assert set(columns) <= set(JOURNAL_ENTRIES.columns)
    assert "has_contract" in INVOICES.columns


def tool(name):
    return next(t for t in LedgerTools().tools() if t.name == name)
