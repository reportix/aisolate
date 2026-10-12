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


@dataclass
class FakeAIsolateManager:
    """A fixed user, by default Noah, a doctor, on the cardiology airlock, the data
    sources a handler asked for, and optionally a stand-in for the data source ``ehr``."""

    principal: Principal = Principal("noah", "noah@example.com", frozenset({"doctor"}))
    airlock: AirlockRef = AirlockRef("test-space", "cardiology", "test")
    ehr: "RecordingConnection | None" = None
    log: logging.Logger = field(default_factory=lambda: logging.getLogger("tests"))
    data_sources_asked: list[str] = field(default_factory=list)

    def llm(self, id: str) -> FakeLlm:
        """Every id is an LLM that embeds."""
        return FakeLlm()

    def data_source(self, id: str) -> Any:
        """``ehr`` when given; every other id is one this airlock does not define."""
        self.data_sources_asked.append(id)
        if self.ehr is not None and id == "ehr":
            return nullcontext(self.ehr)
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


@dataclass(frozen=True)
class Statement:
    """One statement a handler executed: its SQL and its bound values in order."""

    sql: str
    params: tuple[Any, ...]


class RecordingConnection:
    """A database that records every statement a handler executes and the values it binds, and answers
    each query with the ``rows`` it was given, none by default: enough to check what a handler asks and
    what it does with the answer, without a database."""

    def __init__(self, rows: Sequence[tuple[Any, ...]] = ()) -> None:
        self.statements: list[Statement] = []
        self.rows = list(rows)

    def cursor(self) -> "RecordingConnection":
        return self

    def execute(self, sql: str, params: Sequence[Any] = ()) -> None:
        self.statements.append(Statement(sql, tuple(params)))

    def fetchall(self) -> list[tuple[Any, ...]]:
        return list(self.rows)

    def commit(self) -> None:
        pass

    def rollback(self) -> None:
        pass
