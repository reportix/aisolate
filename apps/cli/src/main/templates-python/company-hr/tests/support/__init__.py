"""Hand-written stand-ins for what AIsolate hands the project's code, with no platform behind them."""

import logging
from collections.abc import Sequence
from contextlib import nullcontext
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


Rows = list[tuple[Any, ...]]


class FakeCursor:
    """Answers a query with the rows filed under its last parameter, a document's title; one without parameters
    with the employees whose contracts are filed. Records the parameters it was given."""

    def __init__(self, by_title: dict[str, Rows], executed: list[tuple[Any, ...]]) -> None:
        self.by_title, self.executed = by_title, executed
        self.rows: Rows = []

    def execute(self, sql: str, params: Sequence[Any] = ()) -> None:
        self.executed.append(tuple(params))
        if params:
            self.rows = list(self.by_title.get(params[-1], []))
        else:
            self.rows = [(title.removeprefix("Employment Contract "),) for title in self.by_title]

    def fetchall(self) -> Rows:
        return list(self.rows)


class FakeConnection:
    def __init__(self, by_title: dict[str, Rows], executed: list[tuple[Any, ...]]) -> None:
        self.by_title, self.executed = by_title, executed

    def cursor(self) -> FakeCursor:
        return FakeCursor(self.by_title, self.executed)

    def commit(self) -> None:
        pass

    def rollback(self) -> None:
        pass


@dataclass
class FakeAIsolateManager:
    """A fixed user, by default Eric, an employee, on the hr airlock; the data source
    ``hr`` holds the documents of ``contracts``, rows by title, when given, else no data source exists."""

    principal: Principal = Principal("eric", "eric@example.com", frozenset({"employee"}))
    airlock: AirlockRef = AirlockRef("test-space", "hr", "v1", "test")
    log: logging.Logger = field(default_factory=lambda: logging.getLogger("tests"))
    contracts: dict[str, Rows] | None = None
    executed: list[tuple[Any, ...]] = field(default_factory=list)

    def llm(self, id: str) -> FakeLlm:
        """Every id is an LLM that embeds."""
        return FakeLlm()

    def data_source(self, id: str) -> Any:
        if id == "hr" and self.contracts is not None:
            return nullcontext(FakeConnection(self.contracts, self.executed))
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

