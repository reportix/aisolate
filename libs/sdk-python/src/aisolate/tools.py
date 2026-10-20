"""Tool plugins: the entities a tool may query, the governed query, the tools (mirrors the Java SDK)."""

from __future__ import annotations

import enum
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field, replace
from types import MappingProxyType
from typing import Any, ClassVar, Protocol


class ColumnType(enum.StrEnum):
    TEXT = "text"
    NUMBER = "number"


@dataclass(frozen=True, slots=True)
class Column:
    """One column of an :class:`Entity`: its model-facing name and the SQL expression behind it.

    ``unit``: what every value is in (``EUR``, ``ng/L``, ``%``, ``days``); None without one.
    ``unit_column``: the column of the same entity holding each row's unit; None when fixed or none.
    ``decimals``: how many decimals AIsolate shows of a value or an aggregate of it; None for the
    value's own (an average: two). Negative is a ValueError.
    ``fact``: a text column whose values, typed into an answer's text instead of cited, are shown as
    unsourced (a drug, a diagnosis). A NUMBER column is a ValueError: every typed number is checked
    anyway. Java: ``Column``.
    """

    name: str
    expression: str
    type: ColumnType
    unit: str | None = None
    unit_column: str | None = None
    decimals: int | None = None
    fact: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "type", ColumnType(self.type))
        object.__setattr__(self, "unit", _blank_to_none(self.unit))
        object.__setattr__(self, "unit_column", _blank_to_none(self.unit_column))
        if self.decimals is not None and self.decimals < 0:
            raise ValueError(f"decimals of column {self.name} cannot be negative")
        if self.fact and self.type != ColumnType.TEXT:
            raise ValueError(f"column {self.name} is a number: only a text column can be a fact")

    def with_unit(self, unit: str) -> Column:
        """The same column, every value in ``unit``; clears ``unit_column``."""
        return replace(self, unit=unit, unit_column=None)

    def with_unit_from(self, column: str) -> Column:
        """The same column, each row's unit read from ``column`` of the same entity; clears ``unit``."""
        return replace(self, unit=None, unit_column=column)

    def with_decimals(self, decimals: int) -> Column:
        """The same column, shown with ``decimals`` decimals: ``2`` for money."""
        return replace(self, decimals=decimals)

    def with_fact(self) -> Column:
        """The same column, a fact: a value of it typed into an answer's text is shown as unsourced."""
        return replace(self, fact=True)


@dataclass(frozen=True, slots=True, init=False)
class Entity:
    """Allow-list of what a tool may query. Column names arriving from the model are resolved here or
    rejected; SQL is built from these expressions, never from model text. Java: ``Entity``.

    ``from_`` is the SQL ``FROM`` clause (manifest key ``from``); ``row_key`` the column that orders
    the rows; ``subject`` the column naming whom a row is about (default: ``row_key``; a minimum
    group size counts its distinct values; when its name is the plugin's ``identity_attribute`` a
    row whose value is the asking user's is marked ``self``); ``subject_name`` the column naming the
    subject for a reader; ``period`` the column saying which period a row is about.

    The constructor raises ValueError when a column is declared twice, or ``subject_name``,
    ``period`` or a column's ``unit_column`` names no declared column. Declaration order is the
    result's column order.
    """

    name: str
    from_: str
    row_key: str
    columns: Mapping[str, Column]
    subject: str
    subject_name: str | None
    period: str | None

    def __init__(
        self,
        name: str,
        from_: str,
        row_key: str,
        *columns: Column,
        subject: str | None = None,
        subject_name: str | None = None,
        period: str | None = None,
    ) -> None:
        by_name: dict[str, Column] = {}
        for column in columns:
            if column.name in by_name:
                # Keeping either would silently drop the other's expression.
                raise ValueError(f"entity {name}: column {column.name} is declared twice")
            by_name[column.name] = column
        subject_name = _blank_to_none(subject_name)
        period = _blank_to_none(period)
        # A label naming no column would label every row with nothing, silently.
        _require_declared(name, by_name, subject_name, "subject name")
        _require_declared(name, by_name, period, "period")
        for column in by_name.values():
            _require_declared(name, by_name, column.unit_column, f"unit of {column.name}")
        object.__setattr__(self, "name", name)
        object.__setattr__(self, "from_", from_)
        object.__setattr__(self, "row_key", row_key)
        object.__setattr__(self, "columns", MappingProxyType(by_name))
        object.__setattr__(self, "subject", _blank_to_none(subject) or row_key)
        object.__setattr__(self, "subject_name", subject_name)
        object.__setattr__(self, "period", period)

    def _with(self, **labels: str | None) -> Entity:
        current = {"subject": self.subject, "subject_name": self.subject_name, "period": self.period}
        return Entity(self.name, self.from_, self.row_key, *self.columns.values(), **(current | labels))

    def with_subject(self, column: str) -> Entity:
        return self._with(subject=column)

    def with_subject_name(self, column: str) -> Entity:
        return self._with(subject_name=column)

    def with_period(self, column: str) -> Entity:
        return self._with(period=column)

    def column(self, name: str) -> Column:
        """The column ``name``; ValueError ``Unknown column: <name>`` otherwise."""
        column = self.columns.get(name)
        if column is None:
            raise ValueError(f"Unknown column: {name}")
        return column

    def visible_columns(self, hidden: set[str]) -> list[str]:
        """Columns that may appear in the result (masked ones arrive as ``****``)."""
        return [name for name in self.columns if name not in hidden]

    def queryable_columns(self, hidden: set[str], masked: set[str]) -> list[str]:
        """Columns the model may filter, group or aggregate on: visible and not masked (a predicate on
        a masked column would let the model recover its value)."""
        return [name for name in self.visible_columns(hidden) if name not in masked]


class Op(enum.StrEnum):
    EQ = "eq"
    NEQ = "neq"
    GT = "gt"
    GTE = "gte"
    LT = "lt"
    LTE = "lte"
    ILIKE = "ilike"


class Fn(enum.StrEnum):
    SUM = "sum"
    AVG = "avg"
    COUNT = "count"
    MIN = "min"
    MAX = "max"


@dataclass(frozen=True, slots=True)
class Filter:
    """One condition a tool adds to a governed query: ``column op value``. The column must be one the
    asking user may see; the value is always bound as a parameter, never interpolated. Java: ``Filter``."""

    column: str
    op: Op
    value: Any


@dataclass(frozen=True, slots=True)
class Labels:
    """A result's columns as the entity declares them, each only when the result has that column.

    Java: ``Result.Labels``; the wire JSON is runtime-contract.md §3 (the tool result).
    """

    row_key: str | None = None
    subject: str | None = None
    period: str | None = None
    units: Mapping[str, str] = field(default_factory=dict)
    unit_columns: Mapping[str, str] = field(default_factory=dict)
    decimals: Mapping[str, int] = field(default_factory=dict)
    masked: tuple[str, ...] = ()
    function: str | None = None
    facts: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "units", dict(self.units or {}))
        object.__setattr__(self, "unit_columns", dict(self.unit_columns or {}))
        object.__setattr__(self, "decimals", dict(self.decimals or {}))
        object.__setattr__(self, "masked", tuple(self.masked or ()))
        object.__setattr__(self, "facts", tuple(self.facts or ()))


@dataclass(frozen=True, slots=True)
class Result:
    """What a governed query returns and a tool hands back to the model: the rows, their columns, the
    rule version, the rules that matched, a hash of the SQL shape, and the labels. ``rows`` reach the
    model only; AIsolate strips them before anything reaches the client. Java: ``Result``.

    ``labels`` is None for a result a tool built itself: its figures are then shown without a
    subject, a period or a unit.
    """

    entity: str
    row_count: int
    columns: tuple[str, ...]
    rows: tuple[Mapping[str, Any], ...]
    rule_version: str
    rules: tuple[str, ...] = ()
    sql_hash: str = ""
    labels: Labels | None = None

    def __post_init__(self) -> None:
        # Tools build results from lists; the result stays immutable whatever they pass.
        object.__setattr__(self, "columns", tuple(self.columns))
        object.__setattr__(self, "rows", tuple(self.rows))
        object.__setattr__(self, "rules", tuple(self.rules))

    @classmethod
    def empty(cls, entity: str, rule_version: str) -> Result:
        """A denied query: indistinguishable from "there is nothing to see"."""
        return cls(entity, 0, (), (), rule_version, (), "", None)


class GovernedQuery(Protocol):
    """What a tool may do with the customer's data, bound to the asking user and the airlock of this
    call. The rules decide which rows and columns come back; a filter can only narrow that. A
    denied user gets an empty result, never an error the model could see. Tools never write SQL.

    Java: ``GovernedQuery``.
    """

    def read(self, entity: str, filters: Sequence[Filter] = (), limit: int = 100) -> Result:
        """The rows of ``entity`` the asking user may see, filtered further; ``limit`` is capped at 100."""
        ...

    def aggregate(
        self,
        entity: str,
        function: Fn,
        column: str,
        group_by: str | None = None,
        filters: Sequence[Filter] = (),
    ) -> Result:
        """One aggregate over ``column`` (``count`` over any, the others over a NUMBER one), optionally
        grouped by ``group_by``. Groups under the rules' minimum size are not returned."""
        ...


ToolHandler = Callable[[Any, GovernedQuery, dict[str, Any]], Result]
"""``(plugin_self, query, arguments) -> Result``. Raise ValueError for arguments that do not fit: the
model sees that message and nothing else. Anything but a Result, None included, fails the call with
a generic error, logged under the plugin's name."""


@dataclass(frozen=True, slots=True)
class Tool:
    """One tool a plugin publishes on the tool server. ``input_schema`` is the JSON Schema of the
    arguments, as the model sees it. Java: ``Tool``.

    ``handler`` is the plugin's method, bound: ``handler(query, arguments)``."""

    name: str
    description: str
    input_schema: Mapping[str, Any]
    handler: Callable[[GovernedQuery, dict[str, Any]], Any]


TOOL_MARK = "__aisolate_tool__"


def tool(name: str, description: str, input_schema: Mapping[str, Any]) -> Callable[[ToolHandler], ToolHandler]:
    """Publish a :class:`ToolPlugin` method as a tool::

        @tool("read_records", "Read up to 20 records ...", schema({"entity": ENTITY}, ["entity"]))
        def read_records(self, query: GovernedQuery, arguments: dict[str, Any]) -> Result:
            return query.read(arguments["entity"], filters(arguments.get("filters")), 20)
    """

    def mark(handler: ToolHandler) -> ToolHandler:
        setattr(handler, TOOL_MARK, (name, description, input_schema))
        return handler

    return mark


def tool_marks(cls: type) -> dict[str, tuple[object, tuple[str, str, Mapping[str, Any]]]]:
    """The ``@tool`` attributes of ``cls`` and its bases by attribute name, in definition order (an
    override keeps its base's place): ``{attribute: (class attribute, (name, description, schema))}``."""
    marks: dict[str, tuple[object, tuple[str, str, Mapping[str, Any]]]] = {}
    for klass in reversed(cls.__mro__):
        for attribute, value in vars(klass).items():
            mark = getattr(value, TOOL_MARK, None)
            if mark is not None:
                marks[attribute] = (value, mark)
            else:
                marks.pop(attribute, None)  # overridden by an unmarked attribute: no longer a tool
    return marks


class ToolPlugin:
    """What a project's tool plugin gives the tool server: the entities its tools may query, the data
    source they live in, the identity join, and the tools (its ``@tool`` methods). A plugin holds no
    rules and no credentials. Every concrete subclass in the project's package is one plugin,
    constructed once with no arguments; it serves calls concurrently. Java: ``ToolPlugin``.

    ``identity_join``: a SELECT with one ``%s``, bound to the token's ``email`` claim exactly as the
    token carries it, returning the value row filters compare with as
    ``principal.<identity_attribute>``. ``email = %s`` matches it as the identity provider keeps it (the
    Java templates' ``email = ?``); write ``lower(email) = lower(%s)`` to ignore case. None without one:
    every row filter on that attribute then denies.
    """

    name: ClassVar[str]
    entities: ClassVar[Sequence[Entity]] = ()
    data_source: ClassVar[str | None] = None
    identity_join: ClassVar[str | None] = None
    identity_attribute: ClassVar[str | None] = None

    def tools(self) -> list[Tool]:
        """The ``@tool`` methods of this plugin, bound, in definition order."""
        return [
            Tool(name, description, input_schema, getattr(self, attribute))
            for attribute, (_, (name, description, input_schema)) in tool_marks(type(self)).items()
        ]

    def entity(self, name: str) -> Entity | None:
        return next((entity for entity in self.entities if entity.name == name), None)


def _blank_to_none(value: str | None) -> str | None:
    return None if value is None or not value.strip() else value


def _require_declared(entity: str, columns: Mapping[str, Column], column: str | None, what: str) -> None:
    if column is not None and column not in columns:
        raise ValueError(f"entity {entity}: {what} {column} is not one of its columns")
