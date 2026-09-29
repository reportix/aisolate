import json
from dataclasses import replace

import jsonschema
import pytest
from support import (AIRLOCK, EMPTY, GOOD, IN_FORMAT, PATH, SALARY, Model, ModelUnavailable, Reply,
                     ToolCallFailedException, Tools, Verifier, frames, harness, lines, tool_call)

from aisolate_airlock import audit, prompts
from aisolate_airlock.exchange import MAX_TOOL_CALLS_PER_EXCHANGE
from conftest import SPANS, conformance_dir

pytestmark = pytest.mark.anyio
SCHEMA = json.loads((conformance_dir() / "audit" / "audit-event.schema.json").read_text(encoding="utf-8"))
ASK = {"message": "What is my gross salary?"}
_SERVER = "http post /v1/spaces/{spaceId}/airlocks/{airlockId}/chat"


async def test_given_admitted_question_when_streamed_then_events_in_order_and_one_audit_line():
    # Arrange
    model = Model(Reply("", [tool_call()]), IN_FORMAT)
    tools = Tools(SALARY)
    client, out = harness(model, tools)
    SPANS.clear()

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert response.status_code == 200
    assert response.headers["content-type"] == "text/event-stream;charset=UTF-8"
    assert response.headers["x-aisolate-revision"] == "7b2280eab4b5"
    events = frames(response.text)
    assert [kind for kind, _ in events] == ["tool_call", "evidence", "answer_chunk", "done"]
    assert list(events[1][1]) == ["id", "call_id", "tool", "entity", "row_count", "columns", "rule_version", "rules",
                                  "sql_hash", "data_source", "type"]
    assert events[2][1] == {"content": "Your gross salary is 78,000 EUR.", "type": "answer_chunk"}
    assert events[3][1]["provenance"] == {"status": "all_sourced", "sourced": 1, "computed": 0, "unsourced": 0,
                                          "mismatch": 0}
    assert tools.calls == [("read_my_records", {"entity": "hr.compensation"}, "good",
                            {"airlock": "hr", "revision": "7b2280eab4b5"})]
    shown = model.calls[1]["messages"][-1]["content"]
    assert shown == ('{"evidence_id":"ev-1","row_count":1,"rows":[{"row":"r1","self":true,"employee_id":3,'
                     '"full_name":"Elena Varga","gross_salary":78000.00}]}')
    [line] = lines(out)
    jsonschema.validate(line, SCHEMA)
    assert (line["outcome"], line["evidence"], line["model"]["calls"], line["model"]["input_tokens"]) == (
        "answered", ["ev-1"], 2, 100)
    assert line["tools"][0]["rows_hash"] == audit.rows_hash([{"self": True, "employee_id": 3,
                                                              "full_name": "Elena Varga", "gross_salary": 78000}])
    exchange = next(s for s in SPANS.get_finished_spans() if s.name == "exchange acme/hr")
    assert line["trace_id"] == format(exchange.context.trace_id, "032x")
    assert exchange.attributes["aisolate.outcome"] == "answered"
    assert exchange.attributes["aisolate.figures.sourced"] == "1"


async def test_given_accept_json_when_asked_then_one_document_with_the_twelve_fields():
    # Arrange
    client, _ = harness(Model(Reply("", [tool_call()]), IN_FORMAT), Tools(SALARY))

    # Act
    response = await client.post(PATH, json=ASK, headers={**GOOD, "Accept": "application/json"})

    # Assert
    body = response.json()
    assert list(body) == ["answer", "dont_know", "segments", "figures", "tables", "quotes", "provenance", "evidence",
                          "tool_errors", "verification", "revision", "trace_id"]
    assert body["tool_errors"] == []
    assert body["evidence"] == [{"id": "ev-1", "source": "read_my_records", "name": "hr.compensation", "rows": 1,
                                 "data_source": "hr"}]
    assert body["verification"] == {"verified": False, "verdicts": []}
    assert len(body["trace_id"]) == 32


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer bad"}, {"Authorization": "Basic good"}],
                         ids=["no-token", "bad-token", "not-bearer"])
async def test_given_no_valid_token_when_asked_then_401_with_challenge_and_model_never_called(headers):
    # Arrange
    model = Model()
    client, out = harness(model)

    # Act
    response = await client.post(PATH, json=ASK, headers=headers)

    # Assert
    assert response.status_code == 401
    assert response.text == '{"status":401,"title":"Unauthorized"}'
    assert response.headers["www-authenticate"] == (
        'Bearer resource_metadata="http://aisolate.localhost/.well-known/oauth-protected-resource"')
    assert (model.calls, out.getvalue()) == ([], "")


@pytest.mark.parametrize(("claims", "reason"), [({"email": "ivy@other.example"}, "user"),
                                                ({"azp": "other-app"}, "client")], ids=["user", "client"])
async def test_given_unlisted_user_or_client_when_asked_then_403_and_one_refusal_line(claims, reason):
    # Arrange
    model = Model()
    client, out = harness(model, verifier=Verifier(**claims))
    before = audit.REFUSALS.labels(airlock="hr", reason=reason)._value.get()

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert (response.status_code, response.json()["detail"]) == (403, "forbidden")
    [line] = lines(out)
    jsonschema.validate(line, SCHEMA)
    assert (line["event"], line["outcome"], line["reason"], line["question"]) == (
        "refusal", "refused", reason, ASK["message"])
    assert model.calls == []
    assert audit.REFUSALS.labels(airlock="hr", reason=reason)._value.get() == before + 1


@pytest.mark.parametrize(("azp", "status", "logged"), [("knime", 200, None), ("n8n", 403, "acme/hr does not admit client n8n")],
                         ids=["listed", "unlisted"])
async def test_given_machine_client_when_asked_then_admitted_by_its_client_id_alone(caplog, azp, status, logged):
    # Arrange
    client, _ = harness(Model(Reply("", [tool_call()]), IN_FORMAT), Tools(SALARY),
                        verifier=Verifier(email=None, azp=azp), airlock=replace(AIRLOCK, clients=("knime",)))

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert response.status_code == status
    assert [r.getMessage().split(": ", 1)[1] for r in caplog.records if "does not admit" in r.getMessage()] == (
        [logged] if logged else [])


@pytest.mark.parametrize(("path", "body", "status", "detail"), [
    ("/v1/spaces/other/airlocks/hr/chat", ASK, 404, "unknown airlock"),
    ("/v1/spaces/acme/airlocks/sales/chat", ASK, 404, "unknown airlock"),
    (PATH, {"message": "  "}, 400, "message is blank or too long"),
    (PATH, {"message": "x" * 4001}, 400, "message is blank or too long"),
    (PATH, {"other": "x"}, 400, "message is blank or too long"),
], ids=["space", "airlock", "blank", "too-long", "missing"])
async def test_given_unknown_airlock_or_bad_message_when_asked_then_problem_and_no_audit_line(path, body, status,
                                                                                             detail):
    # Arrange
    client, out = harness(Model())

    # Act
    response = await client.post(path, json=body, headers=GOOD)

    # Assert
    assert (response.status_code, response.json()["detail"]) == (status, detail)
    assert response.headers["content-type"] == "application/problem+json"
    assert out.getvalue() == ""


async def test_given_full_buffer_failing_closed_when_asked_then_503_before_admission(tmp_path):
    # Arrange
    sink = audit.AuditSink("http://backend:8085/v1/audit/events", None, str(tmp_path), 0, True)
    client, out = harness(Model(), sink=sink, verifier=Verifier(email="ivy@other.example"))

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert (response.status_code, response.json()["detail"]) == (503, "audit unavailable")
    assert out.getvalue() == ""


async def test_given_model_invents_a_tool_when_answering_then_told_it_is_not_available_and_no_frame():
    # Arrange
    model = Model(Reply("", [tool_call("drop_tables")]), "Hello, I can help with HR.")
    client, out = harness(model, Tools())

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert [kind for kind, _ in frames(response.text)] == ["answer_chunk", "done"]
    assert model.calls[0]["messages"][-1]["role"] == "user"
    assert model.calls[1]["messages"][-1] == {"role": "tool", "content": "Tool not available: drop_tables",
                                              "tool_name": "drop_tables"}
    assert lines(out)[0]["tools"] == []


async def test_given_rules_grant_no_row_when_answered_then_exactly_i_dont_know():
    # Arrange
    client, out = harness(Model(Reply("", [tool_call()]), "Your salary is 99,000 EUR."), Tools(EMPTY))

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert frames(response.text)[2][1]["content"] == prompts.I_DONT_KNOW
    assert lines(out)[0]["outcome"] == "dont_know"


@pytest.mark.parametrize(("failure", "error", "data_source", "answer"), [
    (ToolCallFailedException("The data source refused the login", "login_refused", "hr"), "login_refused", "hr",
     prompts.COULD_NOT_REACH_DATA),
    (ToolCallFailedException("The data source could not be reached", "unreachable", "hr"), "unreachable", "hr",
     prompts.COULD_NOT_REACH_DATA),
    (ToolCallFailedException("the tool server could not be reached"), "failed", None, prompts.I_DONT_KNOW),
], ids=["login-refused", "unreachable", "failed"])
async def test_given_tool_call_fails_when_answered_then_its_category_reaches_the_stream_and_the_audit(
        failure, error, data_source, answer):
    # Arrange
    model = Model(Reply("", [tool_call()]), "Your salary is 99,000 EUR.")
    client, out = harness(model, Tools(failure))

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert model.calls[1]["messages"][-1]["content"] == f"Error: {failure}"
    assert frames(response.text)[1] == ("tool_error", {"call_id": "call-1", "tool": "read_my_records", "error": error,
                                                       "type": "tool_error"})
    assert frames(response.text)[2][1]["content"] == answer
    assert lines(out)[0]["tools"] == [{"name": "read_my_records", "args": None, "data_source": data_source,
                                      "rows": None, "rule_version": None, "sql_hash": None, "rows_hash": None,
                                      "error": error}]
    jsonschema.validate(lines(out)[0], SCHEMA)


@pytest.mark.parametrize(("second", "answer"), [
    (ToolCallFailedException("The data source could not be reached", "unreachable", "sales"),
     prompts.COULD_NOT_REACH_DATA),
    (ToolCallFailedException("Invalid arguments: unknown op: regex"), prompts.I_DONT_KNOW),
], ids=["every-call-on-its-data-source", "one-call-otherwise"])
async def test_given_two_failed_calls_when_answered_then_could_not_reach_only_when_every_one_failed_on_its_data(
        second, answer):
    # Arrange
    model = Model(Reply("", [tool_call(), tool_call()]), "Your salary is 99,000 EUR.")
    tools = Tools(ToolCallFailedException("The data source refused the login", "login_refused", "hr"), second)
    client, _ = harness(model, tools)

    # Act
    response = await client.post(PATH, json=ASK, headers={**GOOD, "Accept": "application/json"})

    # Assert
    body = response.json()
    assert body["answer"] == answer
    assert [e["error"] for e in body["tool_errors"]] == ["login_refused", second.error]


async def test_given_model_keeps_calling_tools_when_budget_spent_then_answers_without_tools_from_evidence():
    # Arrange: four different calls, so none is a repeat answered from an earlier one
    model = Model(Reply("", [tool_call(arguments={"entity": "hr.compensation", "limit": n}) for n in range(4)]),
                  IN_FORMAT)
    client, out = harness(model, Tools(SALARY, SALARY, SALARY))

    # Act
    await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    last = model.calls[1]
    assert last["tools"] is None
    assert last["messages"][-1]["content"].startswith(prompts.ANSWER_NOW + "\n\n[ev-1] ")
    assert [t["rows"] for t in lines(out)[0]["tools"]] == [1, 1, 1]


async def test_given_model_repeats_a_tool_call_when_answering_then_run_once_and_the_repeat_told_it_has_the_result():
    # Arrange: the same call twice, then the answer
    model = Model(Reply("", [tool_call()]), Reply("", [tool_call()]), IN_FORMAT)
    tools = Tools(SALARY, SALARY)
    client, out = harness(model, tools)

    # Act
    await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert len(tools.calls) == 1
    first, repeat = model.calls[1]["messages"][-1]["content"], model.calls[2]["messages"][-1]["content"]
    assert repeat == prompts.REPEATED_CALL % "read_my_records" + "\n\n" + first
    [line] = lines(out)
    assert (len(line["tools"]), line["evidence"], line["model"]["calls"]) == (1, ["ev-1"], 3)


async def test_given_model_twice_out_of_format_when_grounding_then_asks_in_format_retries_once_and_shows_raw():
    # Arrange
    model = Model(Reply("", [tool_call()]), "It is 78,000 EUR.", "not json", "[1]")
    client, out = harness(model, Tools(SALARY))

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert model.calls[2]["schema"] == prompts.ANSWER_FORMAT_SCHEMA
    assert model.calls[2]["messages"][-1]["content"].startswith(prompts.ANSWER_IN_FORMAT + "\n\n[ev-1] ")
    assert model.calls[3]["messages"][-1]["content"] == prompts.RETRY % "it is not JSON"
    done = frames(response.text)[-1][1]
    assert (done["answer"], done["provenance"]["status"]) == ("It is 78,000 EUR.", "has_unsourced")
    assert lines(out)[0]["model"]["calls"] == 4


async def test_given_model_answers_without_tools_when_tools_offered_then_nudged_once():
    # Arrange
    model = Model("I think 50,000.", Reply("", [tool_call()]), IN_FORMAT)
    client, _ = harness(model, Tools(SALARY))

    # Act
    await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert model.calls[1]["messages"][-2:] == [{"role": "assistant", "content": "I think 50,000."},
                                               {"role": "user", "content": prompts.USE_TOOLS_NUDGE}]


@pytest.mark.parametrize(("accept", "status"), [("text/event-stream", 200), ("application/json", 503)])
async def test_given_model_unreachable_when_asked_then_model_unavailable_and_reason_llm_unavailable(accept, status):
    # Arrange
    client, out = harness(Model(ModelUnavailable("ConnectError")))

    # Act
    response = await client.post(PATH, json=ASK, headers={**GOOD, "Accept": accept})

    # Assert
    assert response.status_code == status
    if status == 200:
        assert frames(response.text) == [("error", {"message": prompts.ERROR_MODEL_UNAVAILABLE, "type": "error"})]
    else:
        assert response.json()["detail"] == prompts.ERROR_MODEL_UNAVAILABLE
    [line] = lines(out)
    assert (line["outcome"], line["reason"], line["model"]["calls"], line["figures"]) == (
        "error", "llm_unavailable", 1, None)


async def test_given_exchange_when_traced_then_no_span_carries_what_was_said_or_who_asked():
    # Arrange
    client, _ = harness(Model(Reply("", [tool_call()]), IN_FORMAT), Tools(SALARY))
    SPANS.clear()

    # Act
    await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    names = [s.name for s in SPANS.get_finished_spans()]
    assert {"admission", "tool read_my_records", "exchange acme/hr", _SERVER} <= set(names)
    said = [ASK["message"], "78,000", "elena", "Elena", "employee", "78000"]
    for span in SPANS.get_finished_spans():
        text = json.dumps({"name": span.name, "attributes": dict(span.attributes or {}),
                           "events": [e.name for e in span.events], "status": span.status.description})
        assert not any(s in text for s in said), text


async def test_given_model_still_calls_tools_when_it_may_not_then_error_and_no_answer():
    # Arrange: each of the two rounds ends only on the request that overruns its own budget
    requests = 2 * (MAX_TOOL_CALLS_PER_EXCHANGE + 1)
    model = Model(*[Reply("", [tool_call()]) for _ in range(requests)])
    client, out = harness(model, Tools(SALARY, SALARY, SALARY))

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert len(model.calls) == requests
    assert model.calls[-1]["tools"] is None
    assert model.calls[-1]["messages"][-1] == {"role": "tool", "content": "Tool not available: read_my_records",
                                               "tool_name": "read_my_records"}
    events = frames(response.text)
    assert events[-1] == ("error", {"message": prompts.ERROR_COULD_NOT_COMPLETE, "type": "error"})
    assert "done" not in [kind for kind, _ in events]
    assert lines(out)[0]["reason"] == "internal"
