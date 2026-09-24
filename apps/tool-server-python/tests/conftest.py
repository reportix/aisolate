import decimal
import json
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

import pytest

from aisolate import Column, Entity, Result, ToolPlugin, arguments, tool

sys.path.insert(0, str(Path(__file__).parent))


def cases_dir() -> Path:
    for parent in Path(__file__).resolve().parents:
        if (parent / "conformance" / "cases").is_dir():
            return parent / "conformance" / "cases"
    raise FileNotFoundError("conformance/cases above " + __file__)


def case_file(relative: str) -> dict[str, Any]:
    return json.loads((cases_dir() / relative).read_text(), parse_float=decimal.Decimal)


def ids(document: dict[str, Any]) -> list[str]:
    return [case["id"] for case in document["cases"]]


REACHED = Result("reached", 0, (), (), "", ("handler",), "", None)
"""What a fake tool returns, so a test can tell an admitted call from a denied one's empty result."""


def entity_of(fixture: Mapping[str, Any]) -> Entity:
    """An entity as the manifest and the conformance fixtures carry it."""
    columns = [
        Column(c["name"], c["expression"], c["type"], c.get("unit"), c.get("unit_column"), c.get("decimals"))
        for c in fixture["columns"]
    ]
    return Entity(
        fixture["name"], fixture["from"], fixture["row_key"], *columns,
        subject=fixture.get("subject"), subject_name=fixture.get("subject_name"), period=fixture.get("period"),
    )


def standard_plugin(entity: Entity, data_source: str, identity_attribute: str | None, identity_join: str | None) -> ToolPlugin:
    """The standard tools as the templates write them: the SDK shapes the raw arguments first."""

    class Standard(ToolPlugin):
        name = "standard"
        entities = (entity,)

        @tool("read_records", "read", arguments.schema({}, []))
        def read_records(self, query, args):
            return query.read(entity.name, arguments.filters(args.get("filters")), arguments.limit(args.get("limit"), 100))

        @tool("aggregate_records", "aggregate", arguments.schema({}, []))
        def aggregate_records(self, query, args):
            return query.aggregate(
                entity.name,
                arguments.function(args.get("function")),
                arguments.string(args.get("column"), "column"),
                None if args.get("group_by") is None else arguments.string(args.get("group_by"), "group_by"),
                arguments.filters(args.get("filters")),
            )

    Standard.data_source = data_source
    Standard.identity_attribute = identity_attribute
    Standard.identity_join = identity_join
    return Standard()


class RecordingDatabase:
    """Records every statement; answers the identity join with ``identity`` and queries with ``rows``."""

    def __init__(self, identity: Any = None, rows: Sequence[dict[str, Any]] = ()) -> None:
        self.identity, self.answer = identity, list(rows)
        self.joins: list[tuple[str, str, list[Any]]] = []
        self.statements: list[tuple[str, str, tuple[Any, ...]]] = []

    def first_value(self, data_source: str, statement: str, params: Sequence[Any]) -> Any:
        self.joins.append((data_source, statement, list(params)))
        return self.identity

    def rows(self, data_source: str, statement: str, params: Sequence[Any], columns: Sequence[str]) -> list[dict[str, Any]]:
        self.statements.append((data_source, statement, tuple(params)))
        return list(self.answer)


def decimals(value: Any) -> Any:
    """Numbers as decimals, so 78000, 78000.00 and 78000.000000 compare equal."""
    if isinstance(value, bool) or value is None or isinstance(value, str):
        return value
    if isinstance(value, int | float | decimal.Decimal):
        return decimal.Decimal(str(value)).normalize()
    if isinstance(value, Mapping):
        return {k: decimals(v) for k, v in value.items()}
    if isinstance(value, list | tuple):
        return [decimals(v) for v in value]
    return value


def result_dict(result: Result) -> dict[str, Any]:
    from aisolate_tool_server.governed import result_json

    document = json.loads(result_json(result, None), parse_float=decimal.Decimal)
    document.pop("data_source")
    return document


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


POSTGRES_IMAGE = "postgres:18"  # what the Java tool server's Testcontainers tests run


@pytest.fixture(scope="session")
def postgres() -> Any:
    """A throwaway Postgres (docker) as ``(host, port, user, password)``; None without docker."""
    import shutil
    import subprocess
    import time

    import pg8000.native

    if shutil.which("docker") is None or subprocess.run(["docker", "info"], capture_output=True).returncode != 0:
        yield None
        return
    container = subprocess.run(
        ["docker", "run", "-d", "--rm", "-e", "POSTGRES_PASSWORD=conformance", "-p", "127.0.0.1::5432", POSTGRES_IMAGE],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    try:
        port = int(subprocess.run(["docker", "port", container, "5432/tcp"], capture_output=True, text=True, check=True)
                   .stdout.strip().splitlines()[0].rsplit(":", 1)[1])
        deadline = time.monotonic() + 60
        while True:
            try:
                pg8000.native.Connection("postgres", host="127.0.0.1", port=port, password="conformance").close()
                break
            except Exception:
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.5)
        yield ("127.0.0.1", port, "postgres", "conformance")
    finally:
        subprocess.run(["docker", "rm", "-f", container], capture_output=True)


def seed(postgres: Any, table: Mapping[str, Any]) -> None:
    """Creates and fills a conformance fixture's table."""
    import pg8000.native

    host, port, user, password = postgres
    schema = table["name"].rpartition(".")[0]
    con = pg8000.native.Connection(user, host=host, port=port, password=password)
    try:
        if schema:
            con.run(f"CREATE SCHEMA IF NOT EXISTS {schema}")
        con.run(f"DROP TABLE IF EXISTS {table['name']}")
        con.run(f"CREATE TABLE {table['name']} ({', '.join(f'{c} {t}' for c, t in table['columns'].items())})")
        for row in table["rows"]:
            names = list(row)
            con.run(
                f"INSERT INTO {table['name']} ({', '.join(names)}) VALUES ({', '.join(':' + n for n in names)})",
                **row,
            )
    finally:
        con.close()
