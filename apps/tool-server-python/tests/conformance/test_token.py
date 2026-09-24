from typing import Any

import httpx2
import pytest
from conftest import RecordingDatabase, case_file, ids
from support_server import Running, token, verifier

from aisolate.build import Catalog
from aisolate_tool_server.app import create_app
from aisolate_tool_server.governed import GovernedQueries, PolicySource

CASES = case_file("admission/token.json")


@pytest.fixture(scope="module")
def url() -> Any:
    queries = GovernedQueries(PolicySource("r", []), Catalog("c", (), ()), {}, RecordingDatabase())
    with Running(create_app(queries, verifier(CASES["issuer"]))) as served:
        yield served


@pytest.mark.parametrize("case", CASES["cases"], ids=ids(CASES))
def test_given_token_case_when_mcp_called_then_valid_or_401_as_java(url: str, case: dict[str, Any]) -> None:
    # Arrange
    headers = {"Accept": "application/json, text/event-stream"}
    if "raw" in case:
        headers["Authorization"] = f"Bearer {case['raw']}"
    elif case["signed_by"] is not None:
        claims = {k: v for k, v in (CASES["base_claims"] | case["claims"]).items() if v is not None}
        headers["Authorization"] = f"Bearer {token(claims, case['signed_by'])}"

    # Act
    response = httpx2.post(f"{url}/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, headers=headers)

    # Assert
    if case["expect"] == "valid":
        assert response.status_code == 200, response.text
        assert response.json()["result"]["tools"] == []
    else:
        assert (response.status_code, response.headers.get("www-authenticate"), response.content) == (401, "Bearer", b"")


def test_given_exp_before_iat_when_mcp_called_then_401_as_spring(url: str) -> None:
    # Arrange
    claims = CASES["base_claims"] | {"exp": -30}
    headers = {"Accept": "application/json, text/event-stream", "Authorization": f"Bearer {token(claims)}"}

    # Act
    response = httpx2.post(f"{url}/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, headers=headers)

    # Assert
    assert response.status_code == 401
