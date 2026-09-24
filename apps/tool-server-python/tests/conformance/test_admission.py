from typing import Any

import pytest
from conftest import REACHED, RecordingDatabase, case_file, ids

from aisolate import Column, ColumnType, Entity, ToolPlugin, arguments, tool
from aisolate.build import Catalog
from aisolate_runtime.config import Access
from aisolate_tool_server.governed import GovernedQueries, Policy, PolicySource

CASES = case_file("admission/airlock.json")
REVISION = "0123456789ab"


class _Plugin(ToolPlugin):
    name = "p"
    data_source = "ds"
    entities = (Entity("e", "t", "id", Column("id", "t.id", ColumnType.NUMBER)),)

    @tool("read_records", "read", arguments.schema({}, []))
    def read_records(self, query, args):
        return REACHED


@pytest.mark.parametrize("case", CASES["cases"], ids=ids(CASES))
def test_given_airlock_case_when_tool_server_admits_then_same_gate_as_the_airlock(case: dict[str, Any]) -> None:
    # Arrange
    own = CASES["airlocks"][case["airlock"]]
    access = Access(tuple(dict.fromkeys(CASES["space"]["users"] + own["users"])), tuple(own["callers"]),
                    tuple(own.get("clients", ())))
    policy = Policy(case["airlock"], REVISION, access, ("read_records",))
    service = GovernedQueries(PolicySource(REVISION, [policy]), Catalog("c", (_Plugin(),), ()), {}, RecordingDatabase())
    meta = {"airlock": case["airlock"], "revision": REVISION}

    # Act
    result = service.call(case["claims"], meta, "read_records", {})

    # Assert
    assert (result is REACHED) == case["expect"]["admitted"]
    if not case["expect"]["admitted"]:
        assert (result.entity, result.rule_version, result.row_count) == ("", REVISION, 0)
