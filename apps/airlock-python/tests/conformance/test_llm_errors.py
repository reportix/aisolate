import decimal
import json
import re

import httpx2
import pytest
from aisolate import AirlockRef, LlmError, Principal

from aisolate_airlock import llm
from aisolate_airlock.exchange import Manager
from conftest import read_case

SHARED = read_case("llm", "errors.json")
START = [c for c in SHARED["cases"] if "call" not in c]
CALLS = [c for c in SHARED["cases"] if "call" in c]


def _env(case: dict) -> dict[str, str]:
    env = {**SHARED["env"], **case.get("env", {})}
    return {k: v for k, v in env.items() if v is not None}


def _decimals(value):
    return json.loads(json.dumps(value), parse_float=decimal.Decimal)


@pytest.mark.parametrize("case", START, ids=[c["id"] for c in START])
def test_given_llm_env_when_the_pod_starts_then_the_catalog_or_the_refusal_of_the_case(case):
    # Arrange
    env = _env(case)
    expect = case["expect"]

    # Act
    try:
        main, llms = llm.catalog(env)
        refused = None
    except ValueError as failed:
        refused = str(failed)

    # Assert
    if "start_error" in expect:
        assert refused == expect["start_error"]
    else:
        assert refused is None and main == expect["main"]
        assert [{"id": m.id, "provider": m.provider, "url": m.url, "model": m.model} for m in llms.values()] == expect["llms"]


@pytest.mark.parametrize("case", CALLS, ids=[c["id"] for c in CALLS])
def test_given_stub_model_server_when_code_calls_a_model_then_the_request_and_outcome_of_the_case(case):
    # Arrange
    sent = []

    def answer(request):
        sent.append({"path": request.url.path, "body": json.loads(request.content, parse_float=decimal.Decimal),
                     "headers": request.headers})
        return httpx2.Response(case["server"]["status"], text=json.dumps(case["server"]["body"], default=float))

    # The stub stands in for scheme, host and port only: a base URL's path, /v1, stays.
    env = {k: (re.sub(r"^https?://[^/]+", "http://stub:11434", v) if k.endswith("_URL") else v)
           for k, v in _env(case).items()}
    _, llms = llm.catalog(env, sync_transport=httpx2.MockTransport(answer))
    manager = Manager(Principal("u-1", "elena@acme.example"), AirlockRef("acme", "hr", "r"), llms=llms)
    call, expect = case["call"], case["expect"]

    # Act
    try:
        result = getattr(manager.llm(call["llm"]), call["op"])(call["input"])
        raised = None
    except Exception as failed:  # noqa: BLE001 - the case says which failure
        raised = failed

    # Assert
    if expect["request"] is None:
        assert sent == []
    else:
        assert [s["path"] for s in sent] == [expect["request"]["path"]]
        assert {k: sent[0]["body"].get(k) for k in expect["request"]["body"]} == expect["request"]["body"]
        headers = expect["request"].get("headers", {})
        assert {k: sent[0]["headers"].get(k) for k in headers} == headers
    if "error" in expect:
        assert isinstance(raised, LlmError) and str(raised) == expect["error"]
    elif expect.get("failure"):
        assert raised is not None and not isinstance(raised, LlmError)
    else:
        assert raised is None and _decimals(result) == expect["result"]
