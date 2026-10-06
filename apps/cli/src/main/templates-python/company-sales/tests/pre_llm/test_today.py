from datetime import date

from support import FakeAIsolateManager, FakeExchange

from acme_sales.pre_llm.today import TodayContext


def test_given_any_question_when_prepared_then_one_line_with_todays_iso_date_and_no_evidence():
    # Arrange
    exchange = FakeExchange("Which invoices are overdue?")
    before = date.today()

    # Act
    TodayContext().prepare(exchange, FakeAIsolateManager())

    # Assert: a run across midnight sees one of the two dates
    assert len(exchange.context) == 1
    assert exchange.context[0] in {f"Today is {before} (ISO date).", f"Today is {date.today()} (ISO date)."}
    assert exchange.evidence == []
