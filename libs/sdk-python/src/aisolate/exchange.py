"""What a handler sees of one chat exchange, and what it may use (mirrors the Java SDK's records)."""

from __future__ import annotations

import enum
import logging
from collections.abc import Sequence
from contextlib import AbstractContextManager
from dataclasses import dataclass, field
from typing import Any, Protocol, overload


@dataclass(frozen=True, slots=True)
class Principal:
    """The asking user: subject (the user id), e-mail when the token carries one, roles.

    Java: ``com.reportix.aisolate.sdk.Principal``.
    """

    sub: str
    email: str | None
    roles: frozenset[str] = frozenset()


@dataclass(frozen=True, slots=True)
class AirlockRef:
    """Which airlock an exchange runs on, and at which revision of its configuration. Java: ``AirlockRef``."""

    space: str
    airlock: str
    revision: str


@dataclass(frozen=True, slots=True)
class Passage:
    """One piece of text for the model's context: where it is from and what it says. Java: ``Passage``."""

    title: str
    section: str
    body: str


@dataclass(frozen=True, slots=True)
class Evidence:
    """One evidence of the exchange: passages a handler added or the JSON a tool returned, verbatim.

    ``id`` is ``ev-n``; ``source`` is the handler that added the passages, or the tool's name;
    ``content_json`` is the evidence's JSON text. Java: ``Evidence``.
    """

    id: str
    source: str
    content_json: str


class Status(enum.StrEnum):
    PASS = "PASS"
    WARN = "WARN"
    FAIL = "FAIL"


@dataclass(frozen=True, slots=True)
class Verdict:
    """One verifier's word on the answer, shown next to it. ``handler`` is set by AIsolate.

    Java: ``Verdict``; build one with :meth:`pass_`, :meth:`warn` or :meth:`fail`.
    """

    status: Status
    note: str = ""
    handler: str | None = None

    def __post_init__(self) -> None:
        # None stays None: the airlock turns a verdict without status into WARN, as Java does.
        if self.status is not None:
            object.__setattr__(self, "status", Status(self.status))
        object.__setattr__(self, "note", "" if self.note is None else self.note)

    @classmethod
    def pass_(cls, note: str = "") -> Verdict:
        """A PASS verdict (``pass`` is a keyword)."""
        return cls(Status.PASS, note)

    @classmethod
    def warn(cls, note: str = "") -> Verdict:
        return cls(Status.WARN, note)

    @classmethod
    def fail(cls, note: str = "") -> Verdict:
        return cls(Status.FAIL, note)


PASS = Status.PASS
WARN = Status.WARN
FAIL = Status.FAIL


@dataclass(frozen=True, slots=True)
class Answer:
    """The text the user gets, the verdicts collected so far, the question as typed, and the grounded answer
    as JSON text (``{answer, segments, figures, tables, quotes, provenance}``),
    empty only for an Answer built without it (the airlock always fills it, a plain answer included).
    Java: ``Answer``."""

    text: str = ""
    verdicts: tuple[Verdict, ...] = field(default_factory=tuple)
    question: str = ""
    grounding_json: str = ""


class Exchange(Protocol):
    """One chat exchange as a handler may read and enrich it. Not thread-safe: use it only in the call
    the runtime made. Java: ``Exchange``."""

    @property
    def question(self) -> str:
        """The user's message as typed."""
        ...

    def add_evidence(self, source: str, passages: Sequence[Passage]) -> str | None:
        """Put passages into the model's context under one evidence id (``ev-n``) and tell the browser
        that ``source`` contributed ``len(passages)`` of them, text withheld.

        Returns the evidence id, or None when ``passages`` is empty: nothing is added then.
        """
        ...

    def add_context(self, text: str) -> None:
        """Add free text to what the model reads before the question (a memory summary, a glossary):
        a user-role message, data to the model rather than the system's instruction."""
        ...

    @property
    def evidence(self) -> list[Evidence]:
        """Every evidence of this exchange so far, passages and tool results, content included."""
        ...


try:
    # What a data source's statements raise, server errors and a lost connection alike (Java:
    # SQLException): the DB-API ``Error`` of the runtime's driver. The SDK pins no driver; a project's
    # own tests without pg8000 get a stand-in their fakes can raise.
    from pg8000.dbapi import Error as DatabaseError
except ImportError:

    class DatabaseError(Exception):  # type: ignore[no-redef]
        """Stand-in for the driver's DB-API ``Error`` where pg8000 is not installed."""


class Connection(Protocol):
    """A DB-API 2.0 (PEP 249) connection; the runtime's is pg8000's (``paramstyle`` ``format``: ``%s``)."""

    def cursor(self) -> Any: ...
    def commit(self) -> None: ...
    def rollback(self) -> None: ...


class LlmError(ValueError):
    """An LLM named wrongly in code: an id the catalog lacks, a model that cannot embed or cannot chat. Its
    message is plain and holds no data, so the airlock logs it whole. Java: ``LlmException``."""


class Llm(Protocol):
    """One LLM of the airlock's catalog, as ``aisolate.llm(id)`` returns it. Java: ``Llm``.

    A failure other than a wrong model (unreachable, timeout, a server error) raises the HTTP client's error.
    """

    @overload
    def embed(self, text: str) -> list[float]: ...
    @overload
    def embed(self, texts: list[str]) -> list[list[float]]: ...
    def embed(self, text):
        """The text's vector, or one vector per text in order. LlmError when the model cannot embed."""
        ...

    def chat(self, prompt: str) -> str:
        """The model's answer to ``prompt`` as one user message. LlmError when the model cannot chat."""
        ...


class AIsolateManager(Protocol):
    """What the airlock pod knows and a handler may use; no key, no token, no credential.

    Java: ``AIsolateManager``.
    """

    @property
    def principal(self) -> Principal:
        """The asking user as the validated token describes them."""
        ...

    @property
    def airlock(self) -> AirlockRef:
        """Which airlock this exchange runs on."""
        ...

    def llm(self, id: str) -> Llm:
        """The LLM ``id`` of this airlock's catalog: its own, its space's or a global one:
        ``aisolate.llm("nomic").embed(exchange.question)``.

        Raises LlmError for an id the catalog lacks: ``no model named <id> in this airlock's space or globally``.
        """
        ...

    def data_source(self, id: str) -> AbstractContextManager[Connection]:
        """The data source ``id`` of this airlock (its own or its space's), opened with
        the credentials AIsolate keeps: ``with aisolate.data_source("hr") as db: ...``. The
        connection goes back to the pool on exit, rolled back unless committed.

        Raises ValueError when the airlock defines no data source ``id``; the message reads
        ``<space>/<airlock> has no data source <id>``.
        """
        ...

    @property
    def log(self) -> logging.Logger:
        """Where a handler logs; lines land in the airlock's own log."""
        ...
