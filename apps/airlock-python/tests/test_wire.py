import dataclasses
import json

import httpx2
import pytest
from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from support import AIRLOCK, GOOD, IN_FORMAT, PATH, SALARY, frames, harness, lines

from aisolate_airlock import jsonw
from aisolate_airlock.llm import Ollama, OpenAi
from aisolate_airlock.mcp_client import ToolServer

pytestmark = pytest.mark.anyio


async def test_given_ollama_and_a_stateless_mcp_server_when_asked_then_the_whole_exchange_over_the_wire():
    # Arrange
    tools = MCPServer("aisolate-tool-server")
    called = []

    @tools.tool()
    def read_my_records(entity: str) -> str:
        """Read my records"""
        called.append(entity)
        return jsonw.dumps(SALARY)

    mcp_app = tools.streamable_http_app(stateless_http=True, json_response=True, transport_security=(
        TransportSecuritySettings(enable_dns_rebinding_protection=False)))
    asked = []
    replies = [{"message": {"role": "assistant", "content": "", "tool_calls": [
        {"function": {"name": "read_my_records", "arguments": {"entity": "hr.compensation"}}}]},
        "prompt_eval_count": 900, "eval_count": 20},
        {"message": {"role": "assistant", "content": IN_FORMAT}, "prompt_eval_count": 1000, "eval_count": 60}]

    def ollama(request):
        asked.append(json.loads(request.content))
        return httpx2.Response(200, json=replies.pop(0))

    client, out = harness(Ollama("qwen", "http://ollama:11434", "qwen", transport=httpx2.MockTransport(ollama)),
                          ToolServer("http://tools:8081/mcp", transport=httpx2.ASGITransport(app=mcp_app)))

    # Act
    async with mcp_app.router.lifespan_context(mcp_app):
        response = await client.post(PATH, json={"message": "What is my gross salary?"}, headers=GOOD)

    # Assert
    assert [kind for kind, _ in frames(response.text)] == ["tool_call", "evidence", "answer_chunk", "done"]
    assert frames(response.text)[2][1]["content"] == "Your gross salary is 78,000 EUR."
    assert called == ["hr.compensation"]
    assert [t["function"]["name"] for t in asked[0]["tools"]] == ["read_my_records"]
    assert asked[1]["messages"][-1]["role"] == "tool"
    assert asked[1]["messages"][-1]["content"].startswith('{"evidence_id":"ev-1","row_count":1,"rows":[{"row":"r1"')
    [line] = lines(out)
    assert (line["model"]["calls"], line["model"]["input_tokens"], line["model"]["output_tokens"]) == (2, 1900, 80)
    assert line["tools"][0]["sql_hash"] == "3f9a1c0d2b7e"


async def test_given_openai_model_and_a_stateless_mcp_server_when_asked_then_the_tool_result_answers_its_call_by_id():
    # Arrange
    tools = MCPServer("aisolate-tool-server")

    @tools.tool()
    def read_my_records(entity: str) -> str:
        """Read my records"""
        return jsonw.dumps(SALARY)

    mcp_app = tools.streamable_http_app(stateless_http=True, json_response=True, transport_security=(
        TransportSecuritySettings(enable_dns_rebinding_protection=False)))
    asked = []
    usage = {"prompt_tokens": 900, "completion_tokens": 20, "total_tokens": 920}
    replies = [{"role": "assistant", "content": None, "tool_calls": [{"id": "call_9", "type": "function", "function": {
        "name": "read_my_records", "arguments": '{"entity": "hr.compensation"}'}}]},
        {"role": "assistant", "content": IN_FORMAT}]

    def openai(request):
        asked.append((request.headers.get("authorization"), json.loads(request.content)))
        return httpx2.Response(200, json={"id": "c", "object": "chat.completion", "created": 1, "model": "gpt",
                                          "choices": [{"index": 0, "message": replies.pop(0), "finish_reason": "stop"}],
                                          "usage": usage})

    model = OpenAi("gpt", "https://api.openai.example/v1", "gpt-4o-mini", api_key="sk-test",
                   transport=httpx2.MockTransport(openai))
    client, out = harness(model, ToolServer("http://tools:8081/mcp", transport=httpx2.ASGITransport(app=mcp_app)),
                          airlock=dataclasses.replace(AIRLOCK, model="openai"))

    # Act
    async with mcp_app.router.lifespan_context(mcp_app):
        response = await client.post(PATH, json={"message": "What is my gross salary?"}, headers=GOOD)

    # Assert
    assert [kind for kind, _ in frames(response.text)] == ["tool_call", "evidence", "answer_chunk", "done"]
    assert frames(response.text)[2][1]["content"] == "Your gross salary is 78,000 EUR."
    assert {authorization for authorization, _ in asked} == {"Bearer sk-test"}
    call, result = asked[1][1]["messages"][-2:]
    assert call["tool_calls"] == [{"id": "call_9", "type": "function", "function": {
        "name": "read_my_records", "arguments": '{"entity": "hr.compensation"}'}}]
    assert (result["role"], result["tool_call_id"]) == ("tool", "call_9")
    assert result["content"].startswith('{"evidence_id":"ev-1","row_count":1,"rows":[{"row":"r1"')
    [line] = lines(out)
    model = line["model"]
    assert (model["provider"], model["name"], model["calls"], model["input_tokens"]) == (
        "openai", "gpt-4o-mini", 2, 1800)
