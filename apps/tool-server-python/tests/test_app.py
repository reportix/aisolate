import json
from typing import Any

import httpx2
import pytest
from conftest import RecordingDatabase
from support_server import CLAIMS, Running, token, verifier

from aisolate import Column, ColumnType, Entity, ToolPlugin, arguments, tool
from aisolate.build import Catalog
from aisolate_runtime.config import Access
from aisolate_tool_server.app import create_app
from aisolate_tool_server.governed import GovernedQueries, Policy, PolicySource
from aisolate_tool_server.rules import RuleSet
from mcp.client.session import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.shared.exceptions import MCPError
from pg8000.exceptions import DatabaseError

REVISION = "0123456789ab"
META = {"airlock": "hr", "revision": REVISION}
ENTITY = Entity("hr.e", "hr.e e", "id", Column("id", "e.id", ColumnType.NUMBER), Column("name", "e.name", ColumnType.TEXT))
RULES = RuleSet.parse(json.dumps({"entity": "hr.e", "rules": [
    {"name": "all", "principals": {"role": "staff"}, "actions": ["read"], "effect": "allow", "row_filter": "true"}]}))


class HrTools(ToolPlugin):
    name = "hr-tools"
    data_source = "hr"
    entities = (ENTITY,)

    @tool("read_records", "Read records", arguments.schema({"limit": arguments.LIMIT_SCHEMA}, []))
    def read_records(self, query, args):
        return query.read("hr.e", arguments.filters(args.get("filters")), arguments.limit(args.get("limit"), 100))

    @tool("unlisted", "Not in the airlock's tools", arguments.schema({}, []))
    def unlisted(self, query, args):
        raise AssertionError("never offered")

    @tool("broken", "Returns no Result", arguments.schema({}, []))
    def broken(self, query, args):
        return "rows"


@pytest.fixture
def served() -> Any:
    database = RecordingDatabase(rows=[{"id": 1, "name": "Nora"}])
    policy = Policy("hr", REVISION, Access(("staff",), ("*",)), ("read_records", "broken"))
    queries = GovernedQueries(PolicySource(REVISION, [policy]), Catalog("p", (HrTools(),), ()), {"hr.e": RULES}, database)
    with Running(create_app(queries, verifier())) as url:
        yield url, database


async def _session_call(url: str, bearer: str, action: Any) -> Any:
    async with httpx2.AsyncClient(headers={"Authorization": f"Bearer {bearer}"}) as http:
        async with streamable_http_client(f"{url}/mcp", http_client=http) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                return await action(session)


@pytest.mark.anyio
async def test_given_valid_token_when_tools_listed_then_only_listed_and_published_tools(served: Any) -> None:
    # Arrange
    url, _ = served

    # Act
    listed = await _session_call(url, token(CLAIMS), lambda s: s.list_tools())

    # Assert
    assert [t.name for t in listed.tools] == ["broken", "read_records"]
    assert listed.tools[1].input_schema == {"type": "object", "properties": {"limit": arguments.LIMIT_SCHEMA}, "required": []}


@pytest.mark.anyio
async def test_given_admitted_call_when_tool_called_then_result_json_with_data_source(served: Any) -> None:
    # Arrange
    url, database = served

    # Act
    result = await _session_call(url, token(CLAIMS), lambda s: s.call_tool("read_records", {"limit": 5}, meta=META))

    # Assert
    evidence = json.loads(result.content[0].text)
    assert not result.is_error
    assert evidence["rows"] == [{"id": 1, "name": "Nora"}] and evidence["data_source"] == "hr"
    assert evidence["rule_version"] == REVISION and evidence["rules"] == ["all"]
    assert database.statements == [("hr", "SELECT e.id AS id, e.name AS name FROM hr.e e ORDER BY e.id LIMIT ?", (5,))]


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("tool", "args", "text", "error"),
    [
        ("read_records", {"limit": "5"}, "Invalid arguments: limit must be a number", None),
        ("broken", {}, "The tool could not complete the request", {"error": "failed", "data_source": "hr"}),
    ],
)
async def test_given_failing_call_when_tool_called_then_fixed_tool_error(
        served: Any, tool: str, args: dict, text: str, error: dict | None) -> None:
    # Arrange
    url, _ = served

    # Act
    result = await _session_call(url, token(CLAIMS), lambda s: s.call_tool(tool, args, meta=META))

    # Assert
    assert result.is_error and result.content[0].text == text
    assert result.meta == error


class FailingDatabase(RecordingDatabase):
    def __init__(self, failure: BaseException) -> None:
        super().__init__()
        self.failure = failure

    def rows(self, data_source: str, statement: str, params: Any, columns: Any) -> list[dict[str, Any]]:
        raise self.failure


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("failure", "text", "error"),
    [
        (DatabaseError({"S": "FATAL", "C": "28P01", "M": "password authentication failed"}),
         "The data source refused the login", "login_refused"),
        (ConnectionRefusedError(61, "Connection refused"), "The data source could not be reached", "unreachable"),
        (DatabaseError({"S": "ERROR", "C": "42P01", "M": "relation e does not exist at SELECT"}),
         "The tool could not complete the request", "failed"),
    ],
)
async def test_given_data_source_failure_when_tool_called_then_its_error_and_data_source_in_meta(
        failure: BaseException, text: str, error: str) -> None:
    # Arrange
    policy = Policy("hr", REVISION, Access(("staff",), ("*",)), ("read_records",))
    queries = GovernedQueries(PolicySource(REVISION, [policy]), Catalog("p", (HrTools(),), ()), {"hr.e": RULES},
                              FailingDatabase(failure))

    # Act
    with Running(create_app(queries, verifier())) as url:
        result = await _session_call(url, token(CLAIMS), lambda s: s.call_tool("read_records", {}, meta=META))

    # Assert: the sentence and the error, never the driver's words
    assert result.is_error and result.content[0].text == text
    assert result.meta == {"error": error, "data_source": "hr"}


@pytest.mark.anyio
async def test_given_other_revision_when_tool_called_then_silent_empty_result(served: Any) -> None:
    # Arrange
    url, database = served

    # Act
    result = await _session_call(url, token(CLAIMS), lambda s: s.call_tool("read_records", {}, meta=META | {"revision": "x"}))

    # Assert
    assert json.loads(result.content[0].text) == {
        "entity": "", "row_count": 0, "columns": [], "rows": [], "rule_version": "", "rules": [], "sql_hash": "",
        "labels": None, "data_source": "hr"}
    assert database.statements == []


@pytest.mark.anyio
async def test_given_unpublished_tool_when_called_then_json_rpc_error(served: Any) -> None:
    # Arrange
    url, _ = served

    # Act
    with pytest.raises(ExceptionGroup) as refused:
        await _session_call(url, token(CLAIMS), lambda s: s.call_tool("unlisted", {}, meta=META))

    # Assert
    assert refused.group_contains(MCPError, match="Unknown tool: unlisted", depth=None)
