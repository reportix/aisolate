"""The model's final answer as AIsolate asks for it: text with placeholders, each a reference into the
exchange's evidence (Java: ``AnswerFormat``)."""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

OPS = (
    "add",
    "subtract",
    "multiply",
    "divide",
    "percent_change",
    "sum",
    "average",
    "min",
    "max",
    "count",
    "top_n",
    "bottom_n",
)

@dataclass(frozen=True)
class Cell:
    evidence: str | None
    row: str | None
    column: str | None


@dataclass(frozen=True)
class By:
    evidence: str | None
    column: str | None


@dataclass(frozen=True)
class Computed:
    op: str | None
    of: list[Cell | None]
    n: int | None
    by: By | None
    rows: list[str]


@dataclass(frozen=True)
class Rank:
    op: str | None
    by: str | None


@dataclass(frozen=True)
class Table:
    evidence: str | None
    rows: list[str]
    columns: list[str]
    rank: Rank | None


@dataclass(frozen=True)
class About:
    subject: str | None
    period: str | None
    concept: str | None
    unit: str | None


@dataclass(frozen=True)
class Ref:
    id: str
    cell: Cell | None
    computed: Computed | None
    table: Table | None
    about: About | None


@dataclass(frozen=True)
class QuoteRef:
    id: str | None
    evidence: str | None
    index: int | None
    text: str | None


@dataclass(frozen=True)
class ModelAnswer:
    text: str
    figures: list[Ref]
    quotes: list[QuoteRef]
    dont_know: bool


# Java's \s is ASCII and its $ also matches before one final line terminator.
_S = "[ \t\n\x0b\f\r]"
END = r"(?=(?:\r\n|[\n\r\u0085\u2028\u2029])?\Z)"
_FENCE = re.compile(rf"^```(?:json)?{_S}*|{_S}*```{END}")

# Character.isWhitespace, what Java's strip() and isBlank() use.
JAVA_WS = "\t\n\x0b\f\r\x1c\x1d\x1e\x1f \u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000"


def java_strip(text: str) -> str:
    return text.strip(JAVA_WS)


def parse(said: str | None) -> ModelAnswer:
    """Raises ValueError saying what is not in the format; the message never quotes the text."""
    text = _FENCE.sub("", java_strip(said or ""))
    if not text.strip(" \t\n\r"):
        # Jackson reads no content as a missing node, not as a syntax error.
        raise ValueError("it is not a JSON object")
    try:
        root = json.loads(text, parse_constant=_no_constant)
    except ValueError:
        raise ValueError("it is not JSON") from None
    if not isinstance(root, dict):
        raise ValueError("it is not a JSON object")
    if not isinstance(root.get("text"), str):
        raise ValueError("text is missing")
    figures = []
    for figure in _array(root, "figures"):
        id = _string(figure, "id")
        if not isinstance(figure, dict) or id is None:
            raise ValueError("every figure needs an id")
        computed = figure.get("computed")
        table = figure.get("table")
        about = figure.get("about")
        figures.append(
            Ref(
                id,
                _cell(figure.get("cell")),
                Computed(
                    _string(computed, "op"),
                    [_cell(item) for item in _items(computed, "of")],
                    computed.get("n") if _integral(computed.get("n")) else None,
                    By(_string(computed["by"], "evidence"), _string(computed["by"], "column"))
                    if isinstance(computed.get("by"), dict)
                    else None,
                    _strings(computed, "rows"),
                )
                if isinstance(computed, dict)
                else None,
                Table(
                    _string(table, "evidence"),
                    _strings(table, "rows"),
                    _strings(table, "columns"),
                    Rank(_string(table["rank"], "op"), _string(table["rank"], "by"))
                    if isinstance(table.get("rank"), dict)
                    else None,
                )
                if isinstance(table, dict)
                else None,
                About(
                    _string(about, "subject"),
                    _string(about, "period"),
                    _string(about, "concept"),
                    _string(about, "unit"),
                )
                if isinstance(about, dict)
                else None,
            )
        )
    quotes = []
    for quote in _array(root, "quotes"):
        passage = quote.get("passage") if isinstance(quote, dict) else None
        index = passage.get("index") if isinstance(passage, dict) else None
        quotes.append(
            QuoteRef(
                _string(quote, "id"),
                _string(passage, "evidence"),
                index if _integral(index) else None,
                _string(quote, "text"),
            )
        )
    return ModelAnswer(root["text"], figures, quotes, _as_boolean(root.get("dont_know")))


def _array(root: dict, field: str) -> list:
    items = root.get(field)
    if items is None:
        return []
    if not isinstance(items, list):
        raise ValueError(f"{field} is not a list")
    return items


def _items(node: Any, field: str) -> list:
    # Jackson's forEach over a non-container node visits nothing.
    value = node.get(field) if isinstance(node, dict) else None
    return list(value.values()) if isinstance(value, dict) else value if isinstance(value, list) else []


def _cell(node: Any) -> Cell | None:
    if not isinstance(node, dict):
        return None
    return Cell(_string(node, "evidence"), _string(node, "row"), _string(node, "column"))


def _strings(node: Any, field: str) -> list[str]:
    # No column or handle is named so: an entry that is not a string resolves nowhere, a mismatch.
    return [_as_string(item) if _value(item) else "(none)" for item in _items(node, field)]


def _string(node: Any, field: str) -> str | None:
    value = node.get(field) if isinstance(node, dict) else None
    return _as_string(value) if _value(value) else None


def _value(value: Any) -> bool:
    return value is not None and not isinstance(value, (dict, list))


def _integral(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _as_boolean(value: Any) -> bool:
    # Jackson's asBoolean(false): true, "true", or a non-zero integer.
    if isinstance(value, bool):
        return value
    if _integral(value):
        return value != 0
    return value == "true"


def _as_string(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        return _java_double(value)
    return str(value)


def _java_double(value: float) -> str:
    """Java's ``Double.toString``: the same shortest digits, scientific below 1e-3 and from 1e7."""
    if math.isnan(value):
        return "NaN"
    if math.isinf(value):
        return "Infinity" if value > 0 else "-Infinity"
    if value == 0 or 1e-3 <= abs(value) < 1e7:
        return repr(value)
    exact = Decimal(repr(value))
    sign, digits, _ = exact.as_tuple()
    text = "".join(map(str, digits)).rstrip("0") or "0"
    return f"{'-' if sign else ''}{text[0]}.{text[1:] or '0'}E{exact.adjusted()}"


def _no_constant(name: str) -> Any:
    raise ValueError(name)
