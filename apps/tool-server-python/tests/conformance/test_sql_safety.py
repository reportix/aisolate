import json
from typing import Any

import pytest
from conftest import case_file, decimals, entity_of, ids

from aisolate import Column, ColumnType, Entity, Fn, Result, arguments
from aisolate_tool_server import sql
from aisolate_tool_server.governed import result_json
from aisolate_tool_server.rules import Obligations, RowPredicate

CASES = case_file("sql-safety/compensation.json")
ENTITY = entity_of(case_file(CASES["fixture"])["fixture"]["entity"])


def _obligations(preset: dict[str, Any]) -> Obligations:
    return Obligations(
        preset["all_rows"], tuple(RowPredicate(r["column"], r["value"]) for r in preset["rows"]),
        frozenset(preset["hidden"]), frozenset(preset["masked"]), preset["min_group_size"], tuple(preset["matched_rules"]),
    )


def _compile(case: dict[str, Any]) -> sql.CompiledQuery:
    obligations = _obligations(CASES["obligations"][case["obligations"]])
    args = case["query"]["args"]
    checked = sql.ToolArgs(ENTITY, ENTITY.queryable_columns(set(obligations.hidden), set(obligations.masked)))
    if case["query"]["action"] == "read":
        # The SDK shapes the raw arguments inside the tool first, in the standard tools' order.
        shaped = checked.read(arguments.filters(args.get("filters")), arguments.limit(args.get("limit"), 100))
        return sql.compile_read(ENTITY, obligations, shaped, case.get("self_key"))
    function = arguments.function(args.get("function"))
    column = arguments.string(args.get("column"), "column")
    group_by = None if args.get("group_by") is None else arguments.string(args.get("group_by"), "group_by")
    shaped = checked.aggregate(function, column, group_by, arguments.filters(args.get("filters")))
    return sql.compile_aggregate(ENTITY, obligations, shaped)


@pytest.mark.parametrize("case", CASES["cases"], ids=ids(CASES))
def test_given_model_arguments_when_compiled_then_statement_or_tool_error_as_java(case: dict[str, Any]) -> None:
    # Arrange
    expect = case["expect"]

    # Act
    try:
        query = _compile(case)
    except ValueError as invalid:
        query = f"Invalid arguments: {invalid}"

    # Assert
    if "error" in expect:
        assert query == expect["error"]
        return
    assert query.sql == expect["sql"]
    assert decimals(list(query.params)) == decimals(expect["params"])
    assert [type(p) for p in query.params if isinstance(p, str)] == [str for p in expect["params"] if isinstance(p, str)]
    assert query.sql_hash == expect["sql_hash"]
    assert list(query.columns) == expect["columns"]
    labels = json.loads(result_json(Result("", 0, (), (), "", (), "", query.labels), None))["labels"]
    assert labels == expect["labels"]


MEDICATIONS = Entity(
    "ehr.medications", "ehr.medications m", "id", Column("id", "m.id", ColumnType.NUMBER),
    Column("drug", "m.drug", ColumnType.TEXT, fact=True), Column("dose", "m.dose", ColumnType.TEXT).with_fact(),
)


@pytest.mark.parametrize(
    ("hidden", "masked", "facts"),
    [
        (set(), set(), ["drug", "dose"]),
        ({"drug"}, set(), ["dose"]),
        (set(), {"dose"}, ["drug"]),
        ({"drug"}, {"dose"}, "absent"),
    ],
)
def test_given_fact_columns_when_read_then_labels_list_the_readable_ones_only_when_any(
    hidden: set[str], masked: set[str], facts: list[str] | str
) -> None:
    # Arrange
    obligations = Obligations(True, (), frozenset(hidden), frozenset(masked), None, ("all",))
    shaped = sql.ToolArgs(MEDICATIONS, MEDICATIONS.queryable_columns(hidden, masked)).read([], 10)

    # Act
    labels = sql.compile_read(MEDICATIONS, obligations, shaped, None).labels
    written = json.loads(result_json(Result("", 0, (), (), "", (), "", labels), None))["labels"]

    # Assert: absent when none, so a result without facts is written as before
    assert written.get("facts", "absent") == facts


def test_given_fact_column_when_aggregated_then_labels_carry_no_facts() -> None:
    # Arrange
    obligations = Obligations(True, (), frozenset(), frozenset(), None, ("all",))
    shaped = sql.ToolArgs(MEDICATIONS, list(MEDICATIONS.columns)).aggregate(Fn.COUNT, "id", "drug", [])

    # Act
    labels = sql.compile_aggregate(MEDICATIONS, obligations, shaped).labels
    written = json.loads(result_json(Result("", 0, (), (), "", (), "", labels), None))["labels"]

    # Assert
    assert labels.facts == () and "facts" not in written


@pytest.mark.parametrize(
    ("statement", "params", "expected"),
    [
        ("SELECT a FROM t WHERE (a = ?) LIMIT ?", [1, 2], "SELECT a FROM t WHERE (a = :p1) LIMIT :p2"),
        ("SELECT '?' AS q, \"x?\" FROM t WHERE b = ?", ["v"], "SELECT '?' AS q, \"x?\" FROM t WHERE b = :p1"),
        ("SELECT a::text FROM t LIMIT ?", [5], "SELECT a::text FROM t LIMIT :p1"),
    ],
)
def test_given_hashed_statement_when_renumbered_then_only_placeholders_outside_quotes_change(
    statement: str, params: list[Any], expected: str
) -> None:
    # Arrange / Act
    text, named = sql.for_driver(statement, params)

    # Assert
    assert text == expected
    assert list(named.values()) == params


def test_given_placeholder_count_mismatch_when_renumbered_then_refused() -> None:
    # Arrange / Act / Assert
    with pytest.raises(ValueError, match="2 placeholders for 1 parameters"):
        sql.for_driver("SELECT ? , ?", [1])


@pytest.mark.parametrize(("value", "text"), [(True, "true"), (42, "42"), (4.5, "4.5"), (1e10, "1.0E10"), (0.0001, "1.0E-4"), (["a", 1], "[a, 1]")])
def test_given_json_value_when_java_string_then_as_string_value_of(value: Any, text: str) -> None:
    # Arrange / Act / Assert
    assert sql.java_string(value) == text
