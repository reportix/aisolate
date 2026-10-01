"""Hand-written stand-ins for what AIsolate hands the project's code, with no platform behind them."""

import logging
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any

from aisolate import AirlockRef, Evidence, Passage, Principal


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
    """A fixed user, by default Eric, an employee, on the hr airlock."""

    principal: Principal = Principal("eric", "eric@example.com", frozenset({"employee"}))
    airlock: AirlockRef = AirlockRef("test-space", "hr", "test")
    log: logging.Logger = field(default_factory=lambda: logging.getLogger("tests"))

    def llm(self, id: str) -> FakeLlm:
        """Every id is an LLM that embeds."""
        return FakeLlm()

    def data_source(self, id: str) -> Any:
        """No database behind it: every id is one this airlock does not define."""
        raise ValueError(f"{self.airlock.space}/{self.airlock.airlock} has no data source {id}")

