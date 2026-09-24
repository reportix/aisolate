import json
from typing import Any

import pytest
from conftest import REACHED, RecordingDatabase, case_file, ids

from aisolate import Column, ColumnType, Entity, ToolPlugin, arguments, tool
from aisolate.build import Catalog
from aisolate_runtime.config import Access
from aisolate_tool_server.governed import QUERIES, GovernedQueries, Policy, PolicySource, result_json

CASES = case_file("admission/tool-server.json")


def _plugin(tool_name: str, data_source: str) -> ToolPlugin:
    entity = Entity(f"e.{tool_name}", "t", "id", Column("id", "t.id", ColumnType.NUMBER))

    class Published(ToolPlugin):
        name = f"plugin-{tool_name}"
        entities = (entity,)
        identity_join = "SELECT 1"

        @tool(tool_name, tool_name, arguments.schema({}, []))
        def handler(self, query, args):
            return REACHED

    Published.data_source = data_source
    return Published()


def _service(database: RecordingDatabase) -> GovernedQueries:
    space = tuple(CASES["space"]["users"])
    policies = [
        Policy(p["airlock"], CASES["served_revision"],
               Access(tuple(dict.fromkeys(space + tuple(p["users"]))), tuple(p["callers"]), tuple(p.get("clients", ()))),
               tuple(p["tools"]))
        for p in CASES["policies"]
    ]
    plugins = tuple(_plugin(name, ds) for name, ds in CASES["published_tools"].items())
    return GovernedQueries(PolicySource(CASES["served_revision"], policies), Catalog("c", plugins, ()), {}, database)


def _count(labels: dict[str, str]) -> float:
    return QUERIES.labels(labels["airlock"], labels["tool"], labels["outcome"])._value.get()


@pytest.mark.parametrize("case", CASES["cases"], ids=ids(CASES))
def test_given_pin_and_token_when_tool_called_then_gated_before_any_query_as_java(case: dict[str, Any]) -> None:
    # Arrange
    database = RecordingDatabase(identity="x")
    service = _service(database)
    expect = case["expect"]
    metric = None if expect["admitted"] else expect["metric"] | {"tool": case["tool"]}
    before = None if metric is None else _count(metric)

    # Act
    result = service.call(case["claims"], case["meta"], case["tool"], {})

    # Assert
    if expect["admitted"]:
        assert result is REACHED
        return
    assert json.loads(result_json(result, service.data_source_of(case["tool"]))) == expect["result"]
    assert _count(metric) == before + 1
    assert database.joins == [] and database.statements == []


def test_given_served_airlocks_when_tools_listed_then_listed_and_published_tools_by_name() -> None:
    # Arrange
    service = _service(RecordingDatabase())

    # Act
    listed = [t.name for t in service.published()]

    # Assert
    assert listed == ["aggregate_records", "export_payslips", "read_records"]
