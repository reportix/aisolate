"""Argument checks and SQL generation (Java: ``ToolArgs``, ``SqlCompiler``, ``CompiledQuery``).

The statement text is Java's byte for byte, with ``?`` placeholders: ``sql_hash`` is computed over
it and lands on every audit line. Only then is it renumbered for the driver (:func:`for_driver`)."""

from __future__ import annotations

import hashlib
import math
import re
from collections.abc import Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

from aisolate import Column, ColumnType, Entity, Filter, Fn, Labels, Op

from aisolate_tool_server.rules import Obligations, java_trim

MAX_LIMIT = 100
MAX_FILTERS = 10  # a prompt-injected argument must not grow the WHERE clause without bound

_OPERATORS = {Op.EQ: "=", Op.NEQ: "<>", Op.GT: ">", Op.GTE: ">=", Op.LT: "<", Op.LTE: "<=", Op.ILIKE: "ILIKE"}
_BIG_DECIMAL = re.compile(r"[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?")


@dataclass(frozen=True, slots=True)
class CompiledQuery:
    sql: str
    params: tuple[Any, ...]
    columns: tuple[str, ...]
    labels: Labels

    @property
    def sql_hash(self) -> str:
        return hashlib.sha256(self.sql.encode()).hexdigest()[:12]


@dataclass(frozen=True, slots=True)
class ReadArgs:
    filters: tuple[Filter, ...]
    limit: int


@dataclass(frozen=True, slots=True)
class AggregateArgs:
    function: Fn
    column: str
    group_by: str | None
    filters: tuple[Filter, ...]


def java_string(value: Any) -> str:
    """``String.valueOf`` of a JSON value as Jackson reads it."""
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, float):
        return _java_double(value)
    if isinstance(value, list):
        return "[" + ", ".join(java_string(v) for v in value) + "]"
    if isinstance(value, dict):
        return "{" + ", ".join(f"{k}={java_string(v)}" for k, v in value.items()) + "}"
    if value is None:
        return "null"
    return str(value)


def _java_double(value: float) -> str:
    """``Double.toString``: plain from 1e-3 up to 1e7, else ``d.dddE<n>``, shortest digits either way."""
    if math.isnan(value) or math.isinf(value):
        return "NaN" if math.isnan(value) else ("Infinity" if value > 0 else "-Infinity")
    if value == 0 or 1e-3 <= abs(value) < 1e7:
        return repr(value)  # Python's repr is fixed-point in this range, with a fraction digit
    sign, digits, exponent = Decimal(repr(value)).normalize().as_tuple()
    mantissa = f"{digits[0]}." + ("".join(map(str, digits[1:])) or "0")
    return f"{'-' if sign else ''}{mantissa}E{exponent + len(digits) - 1}"


class ToolArgs:
    """The model's arguments checked against what the user may see of ``entity``."""

    def __init__(self, entity: Entity, visible: Sequence[str]) -> None:
        self.entity = entity
        self.visible = set(visible)

    def read(self, filters: Sequence[Filter], limit: int) -> ReadArgs:
        return ReadArgs(self._typed(filters), max(1, min(MAX_LIMIT, limit)))

    def aggregate(self, function: Fn | None, column: str | None, group_by: str | None, filters: Sequence[Filter]) -> AggregateArgs:
        if function is None:
            raise ValueError("function is required")
        aggregated = self._visible(column)
        if function != Fn.COUNT and self.entity.column(aggregated).type != ColumnType.NUMBER:
            raise ValueError(f"{function.name} needs a numeric column, got: {aggregated}")
        group = None if group_by is None else self._visible(group_by)
        return AggregateArgs(function, aggregated, group, self._typed(filters))

    def _typed(self, filters: Sequence[Filter] | None) -> tuple[Filter, ...]:
        filters = filters or ()
        if len(filters) > MAX_FILTERS:
            raise ValueError(f"at most {MAX_FILTERS} filters")
        typed = []
        for f in filters:
            column = self._visible(f.column)
            if f.op is None:
                raise ValueError("op is required")
            if f.op == Op.ILIKE and self.entity.column(column).type != ColumnType.TEXT:
                raise ValueError(f"op ilike needs a text column, got: {column}")
            typed.append(Filter(column, f.op, _typed_value(self.entity.column(column), f.value)))
        return tuple(typed)

    def _visible(self, name: str | None) -> str:
        if name is None or name not in self.visible:
            raise ValueError(f"column is not available: {java_string(name)}")
        return name


def _typed_value(column: Column, raw: Any) -> Any:
    if raw is None:
        raise ValueError(f"value is required for filter on {column.name}")
    if column.type == ColumnType.TEXT:
        return java_string(raw)
    if isinstance(raw, int | float | Decimal) and not isinstance(raw, bool):
        return Decimal(java_string(raw)) if isinstance(raw, float) else Decimal(raw)
    text = java_trim(java_string(raw))
    if not _BIG_DECIMAL.fullmatch(text):
        raise ValueError(f"value for numeric column {column.name} is not a number: {java_string(raw)}")
    return Decimal(text)


def own_key(entity: Entity, identity_attribute: str | None, identity_value: Any) -> Any:
    """The user's identity value as the ``self`` key, only for an entity whose subject is the identity attribute."""
    return identity_value if entity.subject == identity_attribute else None


def compile_read(entity: Entity, obligations: Obligations, args: ReadArgs, self_key: Any) -> CompiledQuery:
    params: list[Any] = []
    columns: list[str] = []
    select: list[str] = []
    if self_key is not None:
        select.append(f"({entity.column(entity.subject).expression} = ?) AS self")
        params.append(self_key)
        columns.append("self")
    for name in entity.visible_columns(obligations.hidden):
        masked = name in obligations.masked
        select.append(f"'****' AS {name}" if masked else f"{entity.column(name).expression} AS {name}")
        columns.append(name)
    sql = f"SELECT {', '.join(select)} FROM {entity.from_}"
    sql += _where(params, entity, obligations, args.filters)
    # The user's own row first: under the limit, "my ..." must not depend on where it sorts.
    sql += (" ORDER BY self DESC, " if self_key is not None else " ORDER BY ") + entity.column(entity.row_key).expression
    sql += " LIMIT ?"
    params.append(args.limit)
    return CompiledQuery(sql, tuple(params), tuple(columns), _read_labels(entity, obligations))


def compile_aggregate(entity: Entity, obligations: Obligations, args: AggregateArgs) -> CompiledQuery:
    if obligations.min_group_size is not None and args.function in (Fn.MIN, Fn.MAX):
        # The value is one person's, whatever the group's size.
        raise ValueError(f"function {args.function.name} is not allowed here; use sum, avg or count")
    params: list[Any] = []
    columns: list[str] = []
    sql = "SELECT "
    if args.group_by is not None:
        sql += f"{entity.column(args.group_by).expression} AS {args.group_by}, "
        columns.append(args.group_by)
    sql += f"{args.function.value}({entity.column(args.column).expression}) AS value, count(*) AS n FROM {entity.from_}"
    columns += ["value", "n"]
    sql += _where(params, entity, obligations, args.filters)
    if args.group_by is not None:
        sql += f" GROUP BY {entity.column(args.group_by).expression}"
    if obligations.min_group_size is not None:
        # People, not rows: five readings of one patient are a group of one.
        sql += f" HAVING count(DISTINCT {entity.column(entity.subject).expression}) >= ?"
        params.append(obligations.min_group_size)
    sql += " LIMIT ?"
    params.append(MAX_LIMIT)
    return CompiledQuery(sql, tuple(params), tuple(columns), _aggregate_labels(entity, args))


def for_driver(sql: str, params: Sequence[Any]) -> tuple[str, dict[str, Any]]:
    """The statement for pg8000 native: each ``?`` outside quotes becomes ``:p1..:pn``."""
    out: list[str] = []
    quote: str | None = None
    n = 0
    for c in sql:
        if quote:
            quote = None if c == quote else quote
        elif c in "'\"":
            quote = c
        elif c == "?":
            n += 1
            out.append(f":p{n}")
            continue
        out.append(c)
    if n != len(params):
        raise ValueError(f"the statement has {n} placeholders for {len(params)} parameters")
    return "".join(out), {f"p{i + 1}": v for i, v in enumerate(params)}


def _where(params: list[Any], entity: Entity, obligations: Obligations, filters: Sequence[Filter]) -> str:
    clauses = []
    if not obligations.all_rows:
        rows = []
        for predicate in obligations.rows:
            rows.append(f"{entity.column(predicate.column).expression} = ?")
            params.append(predicate.value)
        clauses.append("(" + " OR ".join(rows) + ")")
    for f in filters:
        clauses.append(f"({entity.column(f.column).expression} {_OPERATORS[f.op]} ?)")
        params.append(_bind_value(f))
    return " WHERE " + " AND ".join(clauses) if clauses else ""


def _bind_value(f: Filter) -> Any:
    """``ilike`` means "contains, case-insensitive" unless the model wrote its own wildcards."""
    if f.op == Op.ILIKE and isinstance(f.value, str) and "%" not in f.value and "_" not in f.value:
        return f"%{f.value}%"
    return f.value


def _read_labels(entity: Entity, obligations: Obligations) -> Labels:
    visible = entity.visible_columns(obligations.hidden)
    masked = [c for c in visible if c in obligations.masked]
    readable = [c for c in visible if c not in obligations.masked]
    units, unit_columns, decimals, facts = {}, {}, {}, []
    for name in readable:
        column = entity.column(name)
        if column.fact:
            facts.append(name)
        if column.decimals is not None:
            decimals[name] = column.decimals
        if column.unit is not None:
            units[name] = column.unit
        elif column.unit_column in readable:
            unit_columns[name] = column.unit_column
    return Labels(
        entity.row_key if entity.row_key in readable else None,
        entity.subject_name if entity.subject_name in readable else None,
        entity.period if entity.period in readable else None,
        units, unit_columns, decimals, tuple(masked), None, tuple(facts),
    )


def _aggregate_labels(entity: Entity, args: AggregateArgs) -> Labels:
    group = args.group_by
    aggregated = entity.column(args.column)
    counting = args.function == Fn.COUNT
    units, unit_columns, decimals = {}, {}, {}
    if group is not None and entity.column(group).unit is not None:
        units[group] = entity.column(group).unit
    if group is not None and entity.column(group).decimals is not None:
        decimals[group] = entity.column(group).decimals
    if not counting and aggregated.decimals is not None:
        decimals["value"] = aggregated.decimals
    if not counting and aggregated.unit is not None:
        units["value"] = aggregated.unit
    elif not counting and group is not None and group == aggregated.unit_column:
        unit_columns["value"] = group
    return Labels(
        group,
        group if group is not None and group == entity.subject_name else None,
        group if group is not None and group == entity.period else None,
        units, unit_columns, decimals, (), args.function.value,
    )
