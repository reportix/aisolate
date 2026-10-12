"""Hand-written stand-ins for what AIsolate hands the project's code, with no platform behind them."""

import logging
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any

from aisolate import AirlockRef, Evidence, Filter, Fn, Passage, Principal


class FakeExchange:
    """An exchange that records what a handler adds."""

    def __init__(self, question: str) -> None:
        self.question = question
        self.context: list[str] = []
        self.evidence: list[Evidence] = []

    def add_evidence(self, source: str, passages: Sequence[Passage]) -> str | None:
        if not passages:
            return None
        evidence_id = f"ev-{len(self.evidence) + 1}"
        self.evidence.append(Evidence(evidence_id, source, repr(list(passages))))
        return evidence_id

    def add_context(self, text: str) -> None:
        self.context.append(text)


class FakeLlm:
    def embed(self, text: str) -> list[float]:
        """One dimension, the text's length: enough to tell two texts apart."""
        return [float(len(text))]


@dataclass
class FakeAIsolateManager:
    """A fixed user, by default Hannah, an auditor, on an audited company's airlock,
    and the data sources a handler asked for."""

    principal: Principal = Principal("hannah", "hannah@example.com", frozenset({"auditor"}))
    airlock: AirlockRef = AirlockRef("test-space", "audit", "test")
    log: logging.Logger = field(default_factory=lambda: logging.getLogger("tests"))
    data_sources_asked: list[str] = field(default_factory=list)

    def llm(self, id: str) -> FakeLlm:
        """Every id is an LLM that embeds."""
        return FakeLlm()

    def data_source(self, id: str) -> Any:
        """No database behind it: every id is one this airlock does not define."""
        self.data_sources_asked.append(id)
        raise ValueError(f"{self.airlock.space}/{self.airlock.airlock} has no data source {id}")


class RecordingQuery:
    """A governed query that records what a tool asks of it and returns no rows."""

    def __init__(self) -> None:
        self.calls: list[tuple[Any, ...]] = []

    def read(self, entity: str, filters: Sequence[Filter] = (), limit: int = 100) -> None:
        self.calls.append(("read", entity, list(filters), limit))

    def aggregate(
        self,
        entity: str,
        function: Fn,
        column: str,
        group_by: str | None = None,
        filters: Sequence[Filter] = (),
    ) -> None:
        self.calls.append(("aggregate", entity, function, column, group_by, list(filters)))
