import pytest
from support import RecordingQuery

from acme_sales.tools.sales_tools import SalesTools


@pytest.mark.parametrize("name", ["read_records", "aggregate_records"])
def test_given_entity_of_no_plugin_when_called_then_refused_naming_the_choices_without_a_query(name):
    # Arrange
    query = RecordingQuery()
    args = {"entity": "clients.payments", "function": "sum", "column": "amount_eur"}

    # Act
    with pytest.raises(ValueError) as thrown:
        tool(name).handler(query, args)

    # Assert
    assert str(thrown.value) == "unknown entity clients.payments; one of [clients.accounts, clients.invoices]"
    assert query.calls == []


def test_given_limit_above_20_when_read_records_then_at_most_20_rows():
    # Arrange
    query = RecordingQuery()
    args = {"entity": "clients.invoices", "limit": 500}

    # Act
    tool("read_records").handler(query, args)

    # Assert
    assert query.calls == [("read", "clients.invoices", [], 20)]


def tool(name):
    return next(t for t in SalesTools().tools() if t.name == name)
