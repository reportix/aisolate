"""The handlers around the model: context preparators and memories before it, verifiers after it.

A handler is a class marked by one of the decorators below; every marked class in the project's
package is one, constructed once with no arguments, serving every exchange of the airlock
concurrently (keep it thread-safe). Its name, what an airlock's ``handlers`` list names, is the class's
``__name__``, as the Java simple class name. Within a stage handlers run by ascending ``order``, then
by name. ``description`` is a two-to-four-word title the UI's graph shows ("Read HR documents");
``data_sources`` the ids of the data sources it reads, which the graph shows as its Source.
"""

from __future__ import annotations

import enum
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Protocol, TypeVar

from aisolate.exchange import AIsolateManager, Answer, Evidence, Exchange, Verdict

T = TypeVar("T", bound=type)


class Stage(enum.StrEnum):
    """Manifest values are the ``aisolate.yaml`` keys that list the stage's handlers."""

    CONTEXT_PREPARATOR = "contextPreparator"
    CONVERSATION_MEMORY = "conversationMemory"
    ANSWER_VERIFIER = "answerVerifier"


class ContextPreparator(Protocol):
    """Runs before the model sees the question; enriches the exchange, decides nothing else. An
    exception is logged and the exchange goes on without what it did not add. Java: ``ContextPreparator``."""

    def prepare(self, exchange: Exchange, aisolate: AIsolateManager) -> None: ...


class ConversationMemory(Protocol):
    """Session memory as the developer keeps it; AIsolate stores nothing for it. Java: ``ConversationMemory``."""

    def recall(self, exchange: Exchange, aisolate: AIsolateManager) -> None:
        """Before the model, after the context preparators: add what this user's history says, as context."""
        ...

    def remember(self, exchange: Exchange, answer: Answer, aisolate: AIsolateManager) -> None:
        """After the answer went out, whatever the verdicts were. Failures are logged, nothing else."""
        ...


class AnswerVerifier(Protocol):
    """Checks the answer against the evidence. A verdict annotates the answer; it never rewrites or
    blocks it. An exception becomes a WARN verdict. Java: ``AnswerVerifier``."""

    def verify(self, answer: Answer, evidence: Sequence[Evidence], aisolate: AIsolateManager) -> Verdict:
        """``answer`` carries the text as the user gets it, the verdicts of the verifiers that ran before, the
        question and the grounding JSON; ``evidence`` every passage and tool result of this exchange, rows
        included."""
        ...


HANDLER_MARK = "__aisolate_handler__"


@dataclass(frozen=True, slots=True)
class HandlerMark:
    """What a handler decorator records on its class."""

    stage: Stage
    order: int
    description: str | None
    data_sources: tuple[str, ...] = ()


_METHODS = {
    Stage.CONTEXT_PREPARATOR: ("prepare",),
    Stage.CONVERSATION_MEMORY: ("recall", "remember"),
    Stage.ANSWER_VERIFIER: ("verify",),
}


def handler_mark(cls: type) -> HandlerMark | None:
    """The stage, order and description a decorator gave ``cls`` or a base of it; None for no handler."""
    return getattr(cls, HANDLER_MARK, None)


def _marker(stage: Stage, order: int, description: str | None, data_sources: Sequence[str]) -> Callable[[T], T]:
    # A bare string would read as one id per letter.
    if isinstance(data_sources, str) or not all(isinstance(d, str) and d.strip() for d in data_sources):
        raise TypeError(f"data_sources must be a list of data source ids, not {data_sources!r}")

    def mark(cls: T) -> T:
        if not isinstance(cls, type):
            raise TypeError(f"@{stage.name.lower()} marks a class, not {cls!r}")
        existing = handler_mark(cls)
        if existing is not None and existing.stage != stage:
            raise TypeError(f"{cls.__qualname__} is marked for more than one handler stage")
        missing = [method for method in _METHODS[stage] if not callable(getattr(cls, method, None))]
        if missing:
            raise TypeError(f"{cls.__qualname__} is a {stage} without {', '.join(missing)}()")
        setattr(cls, HANDLER_MARK, HandlerMark(stage, order, description, tuple(data_sources)))
        return cls

    return mark


def context_preparator(
    order: int = 0, description: str | None = None, data_sources: Sequence[str] = ()
) -> Callable[[T], T]:
    """Mark a class with ``prepare(exchange, aisolate)`` as a context preparator."""
    return _marker(Stage.CONTEXT_PREPARATOR, order, description, data_sources)


def memory(
    order: int = 0, description: str | None = None, data_sources: Sequence[str] = ()
) -> Callable[[T], T]:
    """Mark a class with ``recall(...)`` and ``remember(...)`` as a conversation memory."""
    return _marker(Stage.CONVERSATION_MEMORY, order, description, data_sources)


def verifier(
    order: int = 0, description: str | None = None, data_sources: Sequence[str] = ()
) -> Callable[[T], T]:
    """Mark a class with ``verify(answer, evidence, aisolate) -> Verdict`` as an answer verifier."""
    return _marker(Stage.ANSWER_VERIFIER, order, description, data_sources)
