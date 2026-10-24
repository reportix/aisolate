import json
from pathlib import Path
from collections.abc import Iterator
from typing import Any

import httpx2
import pytest
from conftest import seed
from support_server import CLAIMS, ISSUER, Running, jwks, token
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from aisolate import build
from aisolate_runtime.artifact import ArtifactError
from aisolate_tool_server.__main__ import start
from mcp.client.session import ClientSession
from mcp.client.streamable_http import streamable_http_client

PLUGIN = '''
from aisolate import Column, ColumnType, Entity, ToolPlugin, arguments, tool


class StaffTools(ToolPlugin):
    name = "staff-tools"
    data_source = "staff"
    identity_join = "SELECT id FROM startup.staff WHERE lower(email) = lower(%s)"
    identity_attribute = "id"
    entities = (Entity("staff.people", "startup.staff s", "id",
                       Column("id", "s.id", ColumnType.NUMBER), Column("name", "s.name", ColumnType.TEXT),
                       subject_name="name"),)

    @tool("read_people", "Read people", arguments.schema({}, []))
    def read_people(self, query, args):
        return query.read("staff.people", arguments.filters(args.get("filters")), 20)
'''
RULES = {"entity": "staff.people", "rules": [
    {"name": "own-row", "principals": {"role": "staff"}, "actions": ["read"], "effect": "allow", "row_filter": "id == principal.id"}]}
TABLE = {"name": "startup.staff", "columns": {"id": "integer", "name": "text", "email": "text"},
         "rows": [{"id": 1, "name": "Nora", "email": "nora@example.com"}, {"id": 2, "name": "Eric", "email": "eric@example.com"}]}
DOCUMENTS = {
    "Space-s.yaml": "---\napiVersion: aisolate.dev/v1\nkind: Space\nmetadata:\n  name: s\nspec:\n  access:\n    roles:\n    - staff\n  llms:\n  - id: granite\n    provider: ollama\n    url: http://ollama:11434\n    model: granite4.1:8b\n",
    "Airlock-a.yaml": "---\napiVersion: aisolate.dev/v1\nkind: Airlock\nmetadata:\n  name: a\nspec:\n  space: s\n  access:\n    callers:\n    - '*'\n  llm: granite\n  prompt: Answer.\n  tools:\n  - read_people\n  jar:\n    digest: DIGEST\n    language: python\n    tools:\n    - read_people\n",
}


@pytest.fixture(scope="module")
def deployed(tmp_path_factory: pytest.TempPathFactory) -> Iterator[dict[str, Any]]:
    root = tmp_path_factory.mktemp("startup")
    project = root / "project"
    (project / "src" / "startup_staff").mkdir(parents=True)
    (project / "src" / "startup_staff" / "__init__.py").write_text("")
    (project / "src" / "startup_staff" / "tools.py").write_text(PLUGIN)
    (project / "rules").mkdir()
    (project / "rules" / "staff.people.json").write_text(json.dumps(RULES))
    plugins = root / "plugins"
    artifact = build.build(project, out=plugins / "a.zip")
    config_dir = root / "config"
    config_dir.mkdir()
    for name, text in DOCUMENTS.items():
        (config_dir / name).write_text(text.replace("DIGEST", build.digest(artifact)))
    (root / "code-token").write_text("code-token-of-a\n")

    def code(request: Request) -> Response:
        # The backend's code endpoint: this airlock's token only.
        if request.headers.get("authorization") != "Bearer code-token-of-a":
            return Response(status_code=403)
        return Response(artifact.read_bytes(), media_type="application/octet-stream")

    with Running(Starlette(routes=[Route("/v1/code/s/a/{digest}", code)])) as backend:
        yield {"root": root, "env": {
            "AISOLATE_CONFIG_DIR": str(config_dir), "AISOLATE_CODE_URL": f"{backend}/v1/code/s/a/{build.digest(artifact)}",
            "AISOLATE_CODE_DIGEST": build.digest(artifact).upper(), "AISOLATE_CODE_TOKEN_FILE": str(root / "code-token"),
            "AISOLATE_ISSUER_URI": ISSUER,
            "AISOLATE_DATASOURCE_0_ID": "staff", "AISOLATE_DATASOURCE_0_URL": "jdbc:postgresql://127.0.0.1:1/postgres",
        }}


def _start(deployed: dict[str, Any], env: dict[str, str], name: str) -> Any:
    return start(env, deployed["root"] / f"code-{name}", deployed["root"] / f"fetched-{name}")


@pytest.mark.parametrize(("digest", "token", "problem"), [
    ("sha256:" + "0" * 64, "code-token-of-a", "has digest sha256:.*, not AISOLATE_CODE_DIGEST sha256:0{64}"),
    (None, "code-token-of-b", "answered 403"),
], ids=["digest-mismatch", "other-airlocks-token"])
def test_given_fetched_code_refused_when_started_then_start_fails_naming_why(
        deployed: dict[str, Any], tmp_path: Path, digest: str | None, token: str, problem: str) -> None:
    # Arrange
    (tmp_path / "token").write_text(token)
    env = deployed["env"] | {"AISOLATE_CODE_TOKEN_FILE": str(tmp_path / "token")}
    if digest:
        env["AISOLATE_CODE_DIGEST"] = digest

    # Act / Assert
    with pytest.raises(ArtifactError, match=problem):
        start(env, tmp_path / "code", tmp_path / "fetched")


def test_given_plugin_data_source_missing_when_started_then_refused(deployed: dict[str, Any]) -> None:
    # Arrange
    env = {k: v for k, v in deployed["env"].items() if not k.startswith("AISOLATE_DATASOURCE_")}

    # Act / Assert
    with pytest.raises(RuntimeError, match="plugin staff-tools reads data source staff, which this airlock does not define"):
        _start(deployed, env, "nods")


@pytest.mark.anyio
async def test_given_deployed_airlock_when_user_asks_then_own_row_from_postgres(deployed: dict[str, Any], postgres: Any) -> None:
    # Arrange
    if postgres is None:
        pytest.skip("no docker for Postgres")
    seed(postgres, TABLE)
    host, port, user, password = postgres
    issuer = Starlette(routes=[Route("/certs", lambda _: JSONResponse(jwks()))])
    with Running(issuer) as idp:
        env = deployed["env"] | {
            "AISOLATE_DATASOURCE_0_URL": f"jdbc:postgresql://{host}:{port}/postgres",
            "AISOLATE_DATASOURCE_0_USERNAME": user, "AISOLATE_DATASOURCE_0_PASSWORD": password,
            "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI": f"{idp}/certs",
        }
        with Running(_start(deployed, env, "asked")) as url:
            async with httpx2.AsyncClient(headers={"Authorization": f"Bearer {token(CLAIMS)}"}) as http:
                async with streamable_http_client(f"{url}/mcp", http_client=http) as (read, write):
                    async with ClientSession(read, write) as session:
                        await session.initialize()

                        # Act
                        result = await session.call_tool(
                            "read_people", {}, meta={"airlock": "a", "revision": _revision(env)})

    # Assert
    evidence = json.loads(result.content[0].text)
    assert evidence["rows"] == [{"self": True, "id": 1, "name": "Nora"}]
    assert evidence["data_source"] == "staff" and evidence["rules"] == ["own-row"]


def _revision(env: dict[str, str]) -> str:
    from aisolate_runtime import config

    return config.load(env["AISOLATE_CONFIG_DIR"], None).revision


@pytest.mark.parametrize(
    ("host", "status"),
    [("s-a-mcp-server:8081", 200), ("localhost:8081", 200), ("attacker.example:8081", 421),
     ("s-a-mcp-server.attacker.example:8081", 421)],
)
def test_given_deployed_airlock_when_posted_with_host_then_only_its_service_and_localhost_answer(
        deployed: dict[str, Any], host: str, status: int) -> None:
    # Arrange
    issuer = Starlette(routes=[Route("/certs", lambda _: JSONResponse(jwks()))])
    with Running(issuer) as idp:
        env = deployed["env"] | {"SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI": f"{idp}/certs"}
        with Running(_start(deployed, env, f"host-{status}-{host}")) as url:

            # Act
            response = httpx2.post(
                f"{url}/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                headers={"Host": host, "Authorization": f"Bearer {token(CLAIMS)}",
                         "Accept": "application/json, text/event-stream"})

    # Assert
    assert response.status_code == status
