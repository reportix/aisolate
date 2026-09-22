"""Shapes model-supplied arguments for the common tool inputs (Java: ``Arguments``). Columns and values
are validated later by the tool server, against the entity and the rules of the call."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
import math
import unicodedata
from decimal import Decimal
from typing import Any, Final

from aisolate.tools import Filter, Fn, Op

FILTERS_SCHEMA: Final[Mapping[str, Any]] = {
    "type": "array",
    "description": "Optional filters, combined with AND",
    "items": {
        "type": "object",
        "properties": {
            "column": {"type": "string", "description": "Column name from the entity"},
            "op": {
                "type": "string",
                "enum": ["eq", "neq", "gt", "gte", "lt", "lte", "ilike"],
                "description": "ilike = contains, case-insensitive; use it for names",
            },
            "value": {"type": "string", "description": "Value to compare with"},
        },
        "required": ["column", "op", "value"],
    },
}
LIMIT_SCHEMA: Final[Mapping[str, Any]] = {"type": "integer", "description": "Maximum rows, at most 100"}
FUNCTION_SCHEMA: Final[Mapping[str, Any]] = {"type": "string", "enum": ["sum", "avg", "count", "min", "max"]}


def schema(properties: Mapping[str, Any], required: Sequence[str]) -> dict[str, Any]:
    """A tool's input schema: an object with these properties, some of them required."""
    return {"type": "object", "properties": dict(properties), "required": list(required)}


def filters(raw: Any) -> list[Filter]:
    """The ``filters`` argument; None is no filter. ValueError ``filters must be a list``, ``each filter
    must be an object``, ``column is required``, ``unknown op: <x>`` (op compared case-insensitively)."""
    if raw is None:
        return []
    if not isinstance(raw, list | tuple):
        raise ValueError("filters must be a list")
    shaped = []
    for item in raw:
        if not isinstance(item, Mapping):
            raise ValueError("each filter must be an object")
        shaped.append(Filter(string(item.get("column"), "column"), _member(Op, item.get("op"), "op"), item.get("value")))
    return shaped


def limit(raw: Any, fallback: int) -> int:
    """The ``limit`` argument; None is ``fallback``; a float is truncated; else ValueError ``limit must be
    a number``."""
    if raw is None:
        return fallback
    # JSON true is no number in Java either; Decimal is how exact JSON numbers arrive.
    if isinstance(raw, bool) or not isinstance(raw, int | float | Decimal):
        raise ValueError("limit must be a number")
    # Java's Number.intValue(): a double saturates (NaN is 0), an integer keeps its low 32 bits.
    if isinstance(raw, float):
        return 0 if math.isnan(raw) else int(max(-(2**31), min(2**31 - 1, raw)))
    return (int(raw) + 2**31) % 2**32 - 2**31


def function(raw: Any) -> Fn:
    """The ``function`` argument; ValueError ``function is required`` / ``unknown function: <x>``."""
    return _member(Fn, raw, "function")


def string(raw: Any, field: str) -> str:
    """A required non-blank string argument; ValueError ``<field> is required`` otherwise."""
    if not isinstance(raw, str) or all(_java_whitespace(c) for c in raw):
        raise ValueError(f"{field} is required")
    return raw


def _java_whitespace(c: str) -> bool:
    """``Character.isWhitespace``: Unicode spaces but the non-breaking ones, and \\t to \\r, \\x1c to \\x1f."""
    if c in "\t\n\x0b\f\r\x1c\x1d\x1e\x1f":
        return True
    return unicodedata.category(c) in ("Zs", "Zl", "Zp") and c not in "\u00a0\u2007\u202f"


def _member[E: (Op, Fn)](kind: type[E], raw: Any, field: str) -> E:
    text = string(raw, field)
    # Java: Enum.valueOf(text.trim().toUpperCase()); trim() strips the control characters too.
    name = text.strip("".join(map(chr, range(33)))).upper()
    if name not in kind.__members__:
        raise ValueError(f"unknown {field}: {text}")
    return kind[name]
