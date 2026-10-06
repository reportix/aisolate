from datetime import date

from aisolate import AIsolateManager, Exchange, context_preparator


@context_preparator(description="Add today's date")
class TodayContext:
    """Today's date, for the model: renewals "in the next 90 days" and overdue invoices need it, and
    a model knows no date on its own. Runs first; adds one line of context and no evidence."""

    def prepare(self, exchange: Exchange, aisolate: AIsolateManager) -> None:
        exchange.add_context(f"Today is {date.today().isoformat()} (ISO date).")
