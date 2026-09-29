"""Fakes around the airlock: a scripted model, a tool server, a token verifier, one airlock."""

import io
import json
from decimal import Decimal

import httpx2
from aisolate_runtime.identity import Principal, Unauthorized

from aisolate_airlock import audit, jsonw
from aisolate_airlock.app import Settings, create_app
from aisolate_airlock.exchange import AgentLoop, Airlock
from aisolate_airlock.llm import ModelUnavailable, Reply
from aisolate_airlock.mcp_client import ToolCallFailedException, ToolSpec

AIRLOCK = Airlock(space="acme", id="hr", revision="7b2280eab4b5", model="ollama",
                  users=("elena@acme.example",), callers=("acme-chat",), tools=("read_my_records",),
                  data_sources=("hr",), system_prompt="You answer HR questions.")
PATH = "/v1/spaces/acme/airlocks/hr/chat"
GOOD = {"Authorization": "Bearer good"}
SCHEMA = json.dumps({"type": "object", "properties": {"entity": {"type": "string"}, "limit": {"type": "integer"}}})
SALARY = {"entity": "hr.compensation", "row_count": 1, "columns": ["self", "employee_id", "full_name", "gross_salary"],
          "rows": [{"self": True, "employee_id": 3, "full_name": "Elena Varga", "gross_salary": Decimal("78000.00")}],
          "rule_version": "v1.7b2280eab4b5", "rules": ["own-row"], "sql_hash": "3f9a1c0d2b7e",
          "labels": {"row_key": "employee_id", "subject": "full_name", "period": None, "units": {"gross_salary": "EUR"},
                     "unit_columns": {}, "decimals": {}, "masked": [], "function": None},
          "data_source": "hr"}
EMPTY = {**SALARY, "row_count": 0, "rows": []}
IN_FORMAT = json.dumps({"text": "Your gross salary is {f1}.", "figures": [
    {"id": "f1", "cell": {"evidence": "ev-1", "row": "r1", "column": "gross_salary"}, "about": {"subject": "self"}}],
    "quotes": [], "dont_know": False})


def tool_call(name="read_my_records", arguments=None):
    return {"function": {"name": name, "arguments": {"entity": "hr.compensation"} if arguments is None else arguments}}


class Model:
    provider = "ollama"
    model = "qwen3.6:27b-q4_K_M"

    def __init__(self, *replies):
        self.replies = list(replies)
        self.calls = []

    async def chat(self, messages, tools=None, schema=None, airlock="none"):
        self.calls.append({"messages": [dict(m) for m in messages], "tools": tools, "schema": schema})
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply if isinstance(reply, Reply) else Reply(reply, [], 100, 10)


class Tools:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = []

    async def list_tools(self, bearer, pin):
        return [ToolSpec("read_my_records", "Read my records", SCHEMA), ToolSpec("other", "Not listed", "{}")]

    async def call_tool(self, name, arguments, bearer, pin):
        self.calls.append((name, arguments, bearer, pin))
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result if isinstance(result, str) else jsonw.dumps(result)


class Verifier:
    claims = {"sub": "u-1", "email": "elena@acme.example", "azp": "acme-chat",
              "realm_access": {"roles": ["employee"]}}

    def __init__(self, **claims):
        self.claims = {**self.claims, **claims}

    async def verify(self, token):
        if token != "good":
            raise Unauthorized("bad token")
        return Principal.of(self.claims)


def harness(model, tools=None, verifier=None, handlers=(), sink=None, airlock=AIRLOCK, max_duration=480.0):
    out = io.StringIO()
    audit_log = audit.AuditLog(sink or audit.AuditSink(None, None, None, 0, True), out=out)
    agent = AgentLoop(model, tools or Tools(), list(handlers), audit_log, max_duration)
    app = create_app(airlock, agent, verifier or Verifier(), audit_log,
                     Settings("http://aisolate.localhost", "http://idp/realms/acme"))
    client = httpx2.AsyncClient(transport=httpx2.ASGITransport(app=app), base_url="http://airlock")
    return client, out


def lines(out) -> list[dict]:
    return [json.loads(line) for line in out.getvalue().splitlines()]


def frames(text: str) -> list[tuple[str, dict]]:
    events = []
    for block in text.split("\n\n"):
        if block.strip():
            kind, data = block.split("\n", 1)
            events.append((kind.removeprefix("event: "), json.loads(data.removeprefix("data: "))))
    return events


__all__ = ["ModelUnavailable", "ToolCallFailedException", "Reply"]
