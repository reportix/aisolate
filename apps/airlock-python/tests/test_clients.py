import json

import httpx2
import pytest
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings

from aisolate_airlock import llm, mcp_client, tracing
from conftest import SPANS

pytestmark = pytest.mark.anyio
PIN = {"airlock": "hr", "revision": "7b2280eab4b5"}


def _mcp_app(seen: list):
    server = MCPServer("aisolate-tool-server")

    @server.tool()
    def read_my_records(entity: str) -> str:
        """Read my records"""
        return json.dumps({"entity": entity, "row_count": 0, "rows": []})

    @server.tool()
    def broken(entity: str) -> str:
        """Fails"""
        raise ToolError("Invalid arguments: column is not available: iban")

    app = server.streamable_http_app(stateless_http=True, json_response=True,
                                     transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False))

    async def recording(scope, receive, send):
        if scope["type"] == "http":
            seen.append({k.decode(): v.decode() for k, v in scope["headers"]})
        await app(scope, receive, send)

    return app, recording


async def _with_server(test):
    seen: list = []
    app, recording = _mcp_app(seen)
    async with app.router.lifespan_context(app):
        await test(mcp_client.ToolServer("http://tools:8081", transport=httpx2.ASGITransport(app=recording)), seen)


async def test_given_stateless_mcp_server_when_listed_and_called_then_bearer_meta_and_trace_on_every_request():
    async def test(tools, seen):
        # Arrange
        SPANS.clear()

        # Act
        with tracing.span("tool read_my_records"):
            listed = await tools.list_tools("user-jwt", PIN)
            text = await tools.call_tool("read_my_records", {"entity": "hr.compensation"}, "user-jwt", PIN)

        # Assert
        assert sorted(t.name for t in listed) == ["broken", "read_my_records"]
        assert json.loads(text)["entity"] == "hr.compensation"
        assert all(h["authorization"] == "Bearer user-jwt" and "traceparent" in h for h in seen)
        assert tools.url == "http://tools:8081/mcp"

    await _with_server(test)


async def test_given_tool_error_when_called_then_the_servers_text_as_the_tool_error():
    async def test(tools, seen):
        # Act
        with pytest.raises(mcp_client.ToolCallFailedException) as failed:
            await tools.call_tool("broken", {"entity": "x"}, "user-jwt", PIN)

        # Assert
        assert "column is not available: iban" in str(failed.value)

    await _with_server(test)


@pytest.mark.parametrize(("status", "message"), [(401, mcp_client.REJECTED), (403, mcp_client.REJECTED),
                                                 (500, mcp_client.UNREACHABLE), (None, mcp_client.UNREACHABLE)])
async def test_given_transport_failure_when_called_then_one_of_two_fixed_sentences(status, message):
    # Arrange
    def answer(request):
        if status is None:
            raise httpx2.ConnectError("connection refused to http://tools with Bearer secret")
        return httpx2.Response(status, text="Bearer secret row data")

    tools = mcp_client.ToolServer("http://tools:8081/mcp", transport=httpx2.MockTransport(answer))

    # Act
    with pytest.raises(mcp_client.ToolCallFailedException) as failed:
        await tools.call_tool("read_my_records", {}, "secret", PIN)

    # Assert
    assert (str(failed.value), failed.value.error) == (message, "failed")


@pytest.mark.parametrize(("meta", "error", "data_source"), [
    ({"error": "login_refused", "data_source": "hr"}, "login_refused", "hr"),
    ({"error": "unreachable", "data_source": "hr"}, "unreachable", "hr"),
    ({"error": "failed", "data_source": "hr"}, "failed", "hr"),
    ({"error": "disk_on_fire", "data_source": 7}, "failed", None),
    (None, "failed", None),
], ids=["login-refused", "unreachable", "failed", "unknown", "no-meta"])
async def test_given_error_result_with_meta_when_called_then_its_category_and_data_source(meta, error, data_source):
    # Arrange
    def answer(request):
        result = {"content": [{"type": "text", "text": "The data source refused the login"}], "isError": True,
                  **({"_meta": meta} if meta is not None else {})}
        return httpx2.Response(200, json={"jsonrpc": "2.0", "id": json.loads(request.content)["id"],
                                          "result": result})

    tools = mcp_client.ToolServer("http://tools:8081/mcp", transport=httpx2.MockTransport(answer))

    # Act
    with pytest.raises(mcp_client.ToolCallFailedException) as failed:
        await tools.call_tool("read_my_records", {}, "secret", PIN)

    # Assert
    assert (failed.value.error, failed.value.data_source) == (error, data_source)


async def test_given_ollama_when_asked_then_options_tools_format_and_usage_as_spring_ai_sends_them():
    # Arrange
    sent = []

    def answer(request):
        sent.append(json.loads(request.content))
        return httpx2.Response(200, json={"message": {"role": "assistant", "content": "hi", "tool_calls": []},
                                          "prompt_eval_count": 12, "eval_count": 3})

    model = llm.Ollama("qwen", "http://ollama:11434/", "qwen", transport=httpx2.MockTransport(answer))
    SPANS.clear()

    # Act
    reply = await model.chat([{"role": "user", "content": "q"}],
                             tools=[{"name": "t", "description": "d", "input_schema": '{"type":"object"}'}],
                             schema='{"type":"object"}', airlock="hr")

    # Assert
    assert (reply.text, reply.input_tokens, reply.output_tokens) == ("hi", 12, 3)
    body = sent[0]
    assert (body["model"], body["stream"], body["options"], body["keep_alive"], body["think"], body["format"]) == (
        "qwen", False, {"temperature": 0.1}, "-1m", False, {"type": "object"})
    assert body["tools"] == [{"type": "function", "function": {"name": "t", "description": "d",
                                                               "parameters": {"type": "object"}}}]
    http, span = SPANS.get_finished_spans()
    assert (span.name, span.attributes["gen_ai.usage.input_tokens"], span.attributes["gen_ai.system"],
            span.attributes["aisolate.llm"]) == ("chat qwen", "12", "ollama", "qwen")
    assert (http.name, http.parent.span_id) == ("http post", span.context.span_id)
    assert llm.TOKENS.labels(airlock="hr", gen_ai_token_type="total", gen_ai_operation_name="chat",
                           gen_ai_system="ollama", gen_ai_request_model="qwen")._value.get() >= 15


@pytest.mark.parametrize("failure", ["status", "transport", "body", "cannot_chat"])
async def test_given_model_server_failing_when_asked_then_model_unavailable(failure):
    # Arrange
    def answer(request):
        if failure == "transport":
            raise httpx2.ReadTimeout("timed out")
        if failure == "cannot_chat":  # a main LLM that is an embedding model, as Ollama answers it
            return httpx2.Response(400, json={"error": '"qwen" does not support chat'})
        return httpx2.Response(404 if failure == "status" else 200, text="model not found" if failure == "status"
                               else "not json")

    model = llm.Ollama("qwen", "http://ollama:11434", "qwen", transport=httpx2.MockTransport(answer))

    # Act / Assert
    with pytest.raises(llm.ModelUnavailable):
        await model.chat([{"role": "user", "content": "q"}])


def test_given_ollama_embedding_when_embedded_then_vector_and_tokens_under_the_exchanges_airlock():
    # Arrange
    def answer(request):
        assert json.loads(request.content) == {"model": "nomic", "input": "notice period"}
        return httpx2.Response(200, json={"embeddings": [[0.5, -1]], "prompt_eval_count": 4})

    model = llm.Ollama("nomic", "http://ollama:11434", "nomic", sync_transport=httpx2.MockTransport(answer))
    SPANS.clear()
    before = llm.TOKENS.labels(airlock="hr", gen_ai_token_type="input", gen_ai_operation_name="embedding",
                               gen_ai_system="ollama", gen_ai_request_model="nomic")._value.get()

    # Act
    vector = model.embed("notice period", "hr")

    # Assert
    assert vector == [0.5, -1.0]
    span = next(s for s in SPANS.get_finished_spans() if s.name == "embedding")
    assert {k: span.attributes[k] for k in ("gen_ai.request.model", "aisolate.llm", "gen_ai.usage.input_tokens")} == {
        "gen_ai.request.model": "nomic", "aisolate.llm": "nomic", "gen_ai.usage.input_tokens": "4"}
    assert llm.TOKENS.labels(airlock="hr", gen_ai_token_type="input", gen_ai_operation_name="embedding",
                             gen_ai_system="ollama", gen_ai_request_model="nomic")._value.get() == before + 4


def test_given_handler_chat_when_asked_then_a_chat_span_naming_the_llm_and_tokens_under_the_exchanges_airlock():
    # Arrange
    def answer(request):
        return httpx2.Response(200, json={"message": {"role": "assistant", "content": "Hello."},
                                          "prompt_eval_count": 9, "eval_count": 3})

    model = llm.Ollama("granite", "http://ollama:11434", "granite4.1:8b", sync_transport=httpx2.MockTransport(answer))
    SPANS.clear()

    # Act
    text = model.ask("Say hello.", "hr")

    # Assert
    assert text == "Hello."
    span = next(s for s in SPANS.get_finished_spans() if s.name == "chat granite4.1:8b")
    assert (span.attributes["aisolate.llm"], span.attributes["gen_ai.request.model"],
            span.attributes["gen_ai.usage.output_tokens"]) == ("granite", "granite4.1:8b", "3")
    assert llm.TOKENS.labels(airlock="hr", gen_ai_token_type="total", gen_ai_operation_name="chat",
                             gen_ai_system="ollama", gen_ai_request_model="granite4.1:8b")._value.get() >= 12


def _completion(message: dict, usage: dict | None = None) -> dict:
    return {"id": "chatcmpl-1", "object": "chat.completion", "created": 1760000000, "model": "gpt-4o-mini",
            "choices": [{"index": 0, "message": {"role": "assistant", **message}, "finish_reason": "stop"}],
            **({"usage": usage} if usage else {})}


@pytest.mark.parametrize(("api_key", "bearer"), [("sk-test", "Bearer sk-test"), (None, None), (" ", None)],
                         ids=["key", "none", "blank"])
async def test_given_openai_model_when_asked_then_tools_response_format_and_usage_in_openais_shape(api_key, bearer):
    # Arrange
    sent = []

    def answer(request):
        sent.append((request.url.path, request.headers.get("authorization"), json.loads(request.content)))
        return httpx2.Response(200, json=_completion(
            {"content": None, "tool_calls": [{"id": "call_7", "type": "function", "function": {
                "name": "t", "arguments": '{"entity": "hr.compensation"}'}}]},
            {"prompt_tokens": 12, "completion_tokens": 3, "total_tokens": 15}))

    model = llm.OpenAi("gpt", "https://api.openai.example/v1/", "gpt-4o-mini", api_key=api_key,
                       transport=httpx2.MockTransport(answer))
    SPANS.clear()

    # Act
    reply = await model.chat([{"role": "system", "content": "s"}, {"role": "user", "content": "q"}],
                             tools=[{"name": "t", "description": "d", "input_schema": '{"type":"object"}'}],
                             schema='{"type":"object"}', airlock="hr")

    # Assert
    assert (reply.text, reply.input_tokens, reply.output_tokens) == ("", 12, 3)
    assert reply.tool_calls == [{"id": "call_7", "function": {"name": "t",
                                                              "arguments": '{"entity": "hr.compensation"}'}}]
    path, authorization, body = sent[0]
    assert (path, authorization) == ("/v1/chat/completions", bearer)
    assert body == {"model": "gpt-4o-mini",
                    "messages": [{"role": "system", "content": "s"}, {"role": "user", "content": "q"}],
                    "tools": [{"type": "function", "function": {"name": "t", "description": "d",
                                                                "parameters": {"type": "object"}}}],
                    "response_format": {"type": "json_schema", "json_schema": {
                        "name": "json_schema", "strict": False, "schema": {"type": "object"}}}}
    span = next(s for s in SPANS.get_finished_spans() if s.name == "chat gpt-4o-mini")
    assert (span.attributes["gen_ai.system"], span.attributes["aisolate.llm"]) == ("openai", "gpt")
    assert llm.TOKENS.labels(airlock="hr", gen_ai_token_type="total", gen_ai_operation_name="chat",
                             gen_ai_system="openai", gen_ai_request_model="gpt-4o-mini")._value.get() >= 15


def test_given_a_tool_round_when_sent_to_openai_then_each_result_answers_its_call_by_id_in_order():
    # Arrange
    conversation = [
        {"role": "user", "content": "q"},
        {"role": "assistant", "content": "", "tool_calls": [
            {"id": "call_a", "function": {"name": "t", "arguments": '{"entity": "a"}'}},
            {"function": {"name": "u", "arguments": {"entity": "b"}}}]},
        {"role": "tool", "content": "result a", "tool_name": "t"},
        {"role": "tool", "content": "result b", "tool_name": "u"}]

    # Act
    sent = llm._openai_messages(conversation)

    # Assert
    assert sent == [
        {"role": "user", "content": "q"},
        {"role": "assistant", "content": "", "tool_calls": [
            {"id": "call_a", "type": "function", "function": {"name": "t", "arguments": '{"entity": "a"}'}},
            {"id": "call_1_1", "type": "function", "function": {"name": "u", "arguments": '{"entity": "b"}'}}]},
        {"role": "tool", "tool_call_id": "call_a", "content": "result a"},
        {"role": "tool", "tool_call_id": "call_1_1", "content": "result b"}]


@pytest.mark.parametrize("failure", ["status", "unauthorized", "transport", "body", "no_choice"])
async def test_given_openai_server_failing_when_asked_then_model_unavailable(failure):
    # Arrange
    def answer(request):
        if failure == "transport":
            raise httpx2.ReadTimeout("timed out")
        return {"status": httpx2.Response(500, text="out of memory"),
                "unauthorized": httpx2.Response(401, text="Incorrect API key provided: sk-test"),
                "body": httpx2.Response(200, text="not json"),
                "no_choice": httpx2.Response(200, json={"choices": []})}[failure]

    model = llm.OpenAi("gpt", "https://api.openai.example/v1", "gpt-4o-mini", api_key="sk-test",
                       transport=httpx2.MockTransport(answer))

    # Act / Assert
    with pytest.raises(llm.ModelUnavailable) as failed:
        await model.chat([{"role": "user", "content": "q"}])
    assert "sk-test" not in str(failed.value)


@pytest.mark.parametrize(("provider", "kind"), [("ollama", llm.Ollama), ("openai", llm.OpenAi)])
def test_given_a_provider_when_the_catalog_is_read_then_its_client(provider, kind):
    # Arrange
    env = {"AISOLATE_LLM": "m", "AISOLATE_LLM_0_ID": "m", "AISOLATE_LLM_0_PROVIDER": provider,
           "AISOLATE_LLM_0_URL": "http://models:11434/v1", "AISOLATE_LLM_0_MODEL": "x",
           "AISOLATE_LLM_0_API_KEY": "sk-secret-77"}

    # Act
    _, llms = llm.catalog(env)

    # Assert
    assert type(llms["m"]) is kind and llms["m"].provider == provider
    assert "sk-secret-77" not in repr(llms["m"])


@pytest.mark.parametrize("model,temperature", [
    (llm.OpenAi("g", "http://x/v1", "gpt-5"), None),
    (llm.Ollama("q", "http://x", "q"), 0.1)])
def test_given_provider_when_body_built_then_only_ollama_carries_temperature(model, temperature):
    # Arrange
    messages = [{"role": "user", "content": "q"}]

    # Act
    body = model._body(messages) if hasattr(model, "_body") else model._chat_body(messages)

    # Assert
    sent = body.get("options", body).get("temperature")
    assert sent == temperature
