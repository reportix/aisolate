from decimal import Decimal
from typing import Any

import pytest

from aisolate import Filter, Fn, Op, arguments


@pytest.mark.parametrize(
    ("raw", "message"),
    [
        ("x", "filters must be a list"),
        ({"column": "a"}, "filters must be a list"),
        (["x"], "each filter must be an object"),
        ([{"op": "eq", "value": 1}], "column is required"),
        ([{"column": " ", "op": "eq"}], "column is required"),
        ([{"column": "a", "value": 1}], "op is required"),
        ([{"column": "a", "op": 3}], "op is required"),
        ([{"column": "a", "op": "like"}], "unknown op: like"),
    ],
)
def test_given_malformed_filters_when_shaped_then_value_error(raw: Any, message: str) -> None:
    # Arrange / Act / Assert
    with pytest.raises(ValueError, match=f"^{message}$"):
        arguments.filters(raw)


def test_given_filters_when_shaped_then_op_case_insensitive_and_value_untouched() -> None:
    # Arrange
    raw = [{"column": "name", "op": " ILike ", "value": 42}, {"column": "x", "op": "gte"}]

    # Act
    shaped = arguments.filters(raw)

    # Assert
    assert shaped == [Filter("name", Op.ILIKE, 42), Filter("x", Op.GTE, None)]


def test_given_none_when_filters_then_no_filter() -> None:
    # Arrange / Act / Assert
    assert arguments.filters(None) == []


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, 20), (5, 5), (7.9, 7), (Decimal("3.5"), 3), (-2, -2), (500, 500),
        # Java's Number.intValue(): a double saturates (NaN is 0), an integer keeps its low 32 bits.
        (1e20, 2**31 - 1), (-1e20, -(2**31)), (float("nan"), 0), (float("inf"), 2**31 - 1), (2**32 + 1, 1), (2**31, -(2**31)),
    ],
)
def test_given_limit_when_read_then_truncated_fallback_or_java_int_value(raw: Any, expected: int) -> None:
    # Arrange / Act / Assert
    assert arguments.limit(raw, 20) == expected


@pytest.mark.parametrize("raw", ["10", True, [1], {}])
def test_given_no_number_when_limit_then_value_error(raw: Any) -> None:
    # Arrange / Act / Assert
    with pytest.raises(ValueError, match="^limit must be a number$"):
        arguments.limit(raw, 20)


@pytest.mark.parametrize(("raw", "expected"), [("sum", Fn.SUM), ("AVG", Fn.AVG), (" Count", Fn.COUNT)])
def test_given_function_name_when_read_then_member(raw: str, expected: Fn) -> None:
    # Arrange / Act / Assert
    assert arguments.function(raw) == expected


@pytest.mark.parametrize(("raw", "message"), [(None, "function is required"), ("", "function is required"), ("median", "unknown function: median")])
def test_given_bad_function_when_read_then_value_error(raw: Any, message: str) -> None:
    # Arrange / Act / Assert
    with pytest.raises(ValueError, match=f"^{message}$"):
        arguments.function(raw)


def test_given_properties_when_schema_then_object_schema() -> None:
    # Arrange / Act
    result = arguments.schema({"filters": arguments.FILTERS_SCHEMA}, ["filters"])

    # Assert
    assert result == {"type": "object", "properties": {"filters": arguments.FILTERS_SCHEMA}, "required": ["filters"]}


@pytest.mark.parametrize(("raw", "required"), [(" ", False), (" ", True), ("\x1f\t", True), ("x", False)])
def test_given_unicode_spaces_when_string_read_then_blank_as_java(raw: str, required: bool) -> None:
    # Arrange / Act
    try:
        arguments.string(raw, "entity")
        refused = False
    except ValueError:
        refused = True

    # Assert
    assert refused == required
