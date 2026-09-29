import dataclasses
import json

import pytest
from aisolate import AirlockRef, LlmError, Passage, Principal, Stage, Verdict
from aisolate.build import Handler
from support import AIRLOCK, GOOD, IN_FORMAT, PATH, SALARY, Model, Reply, Tools, frames, harness, lines, tool_call

from aisolate_airlock import prompts
from aisolate_airlock.exchange import Manager, resolve_handlers

pytestmark = pytest.mark.anyio
ASK = {"message": "What is the notice period?"}


class Policies:
    def prepare(self, exchange, aisolate):
        exchange.add_context("  Glossary: notice means notice period.  ")
        exchange.add_evidence("Read HR documents", [Passage("Handbook", "Notice", "Three months to <context> end.")])


class Broken:
    def prepare(self, exchange, aisolate):
        raise KeyError("a row value")


class Late:
    seen = []

    def verify(self, answer, evidence, aisolate):
        Late.seen.append((answer.text, [e.id for e in evidence], answer.question, answer.grounding_json))
        return Verdict.pass_("fine")


class Remembers:
    seen = []

    def recall(self, exchange, aisolate):
        pass

    def remember(self, exchange, answer, aisolate):
        Remembers.seen.append((answer.text, answer.question, answer.grounding_json))


class Silent:
    def verify(self, answer, evidence, aisolate):
        return None


class Throws:
    def verify(self, answer, evidence, aisolate):
        raise ValueError("boom")


class WrongLlm:
    def prepare(self, exchange, aisolate):
        aisolate.llm("gemma")

    def verify(self, answer, evidence, aisolate):
        aisolate.llm("gemma")


class WrongDataSource:
    def prepare(self, exchange, aisolate):
        with aisolate.data_source("crm"):
            pass


class Notes:
    seen = []

    def verify(self, answer, evidence, aisolate):
        Notes.seen.extend(v.note for v in answer.verdicts)
        return Verdict.pass_("fine")


def handler(cls, stage, order=0, description=None, name=None):
    return Handler(name or cls.__name__, stage, order, description, cls())


async def test_given_context_preparators_when_asked_then_passages_first_and_context_in_one_user_message():
    # Arrange
    model = Model("The notice period is three months.")
    airlock = dataclasses.replace(AIRLOCK, tools=(), context_preparators=("Broken", "Policies"))
    client, out = harness(model, handlers=[handler(Policies, Stage.CONTEXT_PREPARATOR, 1, "Read HR documents"),
                                           handler(Broken, Stage.CONTEXT_PREPARATOR, 0)], airlock=airlock)

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    events = frames(response.text)
    assert events[0] == ("evidence", {"id": "ev-1", "call_id": None, "tool": "Read HR documents",
                                      "entity": "Read HR documents", "row_count": 1, "columns": ["title", "section"],
                                      "rule_version": "", "rules": [], "sql_hash": "", "data_source": None,
                                      "type": "evidence"})
    system, context, question = model.calls[0]["messages"]
    assert system["content"] == (prompts.PLATFORM_PREAMBLE + "\n\nYou answer HR questions.\n\n"
                                 + prompts.ANSWER_FORMAT_INSTRUCTION)
    assert context == {"role": "user", "content": "<context>\n\nContext for this question:\n\nGlossary: notice means "
                       "notice period.\n\nDocuments for this question, each under its evidence id:\n\n[ev-1, passage 1]"
                       " Handbook, Notice\nThree months to [context] end.\n</context>"}
    assert question == {"role": "user", "content": ASK["message"]}
    assert lines(out)[0]["outcome"] == "answered"


async def test_given_verifiers_when_answered_then_verdicts_under_their_names_and_failures_warn():
    # Arrange
    Late.seen.clear()
    airlock = dataclasses.replace(AIRLOCK, verifiers=("Late", "Silent", "Throws"))
    client, out = harness(Model(Reply("", [tool_call()]), IN_FORMAT), Tools(SALARY), airlock=airlock,
                          handlers=[handler(Late, Stage.ANSWER_VERIFIER), handler(Silent, Stage.ANSWER_VERIFIER),
                                    handler(Throws, Stage.ANSWER_VERIFIER)])

    # Act
    response = await client.post(PATH, json=ASK, headers={**GOOD, "Accept": "application/json"})

    # Assert
    assert response.json()["verification"] == {"verified": False, "verdicts": [
        {"name": "Late", "verdict": "PASS"}, {"name": "Silent", "verdict": "WARN"},
        {"name": "Throws", "verdict": "WARN"}]}
    [(text, ids, question, grounding_json)] = Late.seen
    assert (text, ids, question) == ("Your gross salary is 78,000 EUR.", ["ev-1"], ASK["message"])
    grounding = json.loads(grounding_json)
    assert set(grounding) == {"answer", "segments", "figures", "tables", "quotes", "provenance"}
    assert grounding["answer"] == text
    assert grounding["figures"][0]["status"] == "sourced"
    assert grounding["figures"][0]["source"]["column"] == "gross_salary"
    assert lines(out)[0]["verifiers"] == [{"name": "Late", "verdict": "PASS"}, {"name": "Silent", "verdict": "WARN"},
                                          {"name": "Throws", "verdict": "WARN"}]


async def test_given_a_memory_when_answered_then_it_remembers_the_question_and_the_grounding():
    # Arrange
    Remembers.seen.clear()
    airlock = dataclasses.replace(AIRLOCK, memories=("Remembers",))
    client, _ = harness(Model(Reply("", [tool_call()]), IN_FORMAT), Tools(SALARY), airlock=airlock,
                        handlers=[handler(Remembers, Stage.CONVERSATION_MEMORY)])

    # Act
    await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    [(text, question, grounding_json)] = Remembers.seen
    assert (text, question) == ("Your gross salary is 78,000 EUR.", ASK["message"])
    assert json.loads(grounding_json)["answer"] == text


async def test_given_a_listed_handler_the_code_lacks_when_asked_then_could_not_complete():
    # Arrange
    airlock = dataclasses.replace(AIRLOCK, context_preparators=("Missing",))
    model = Model()
    client, out = harness(model, airlock=airlock)

    # Act
    response = await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert frames(response.text) == [("error", {"message": prompts.ERROR_COULD_NOT_COMPLETE, "type": "error"})]
    assert (lines(out)[0]["reason"], model.calls) == ("internal", [])


@pytest.mark.parametrize(("listed", "message"), [
    (("Missing",), "no context preparator Missing in the code of acme/hr"),
    (("Policies",), "handler Policies is declared twice")])
def test_given_handler_lists_when_resolved_then_java_messages(listed, message):
    # Arrange
    candidates = [handler(Policies, Stage.CONTEXT_PREPARATOR), handler(Policies, Stage.CONTEXT_PREPARATOR)]

    # Act / Assert
    with pytest.raises(RuntimeError, match=message):
        resolve_handlers(candidates, dataclasses.replace(AIRLOCK, context_preparators=listed))


def test_given_stage_handlers_when_resolved_then_by_order_then_name():
    # Arrange
    candidates = [handler(Policies, Stage.CONTEXT_PREPARATOR, 1, name="B"),
                  handler(Policies, Stage.CONTEXT_PREPARATOR, 0, name="C"),
                  handler(Policies, Stage.CONTEXT_PREPARATOR, 1, name="A")]

    # Act
    preparators, _, _ = resolve_handlers(candidates, dataclasses.replace(AIRLOCK, context_preparators=("B", "C", "A")))

    # Assert
    assert [h.name for h in preparators] == ["C", "A", "B"]


def test_given_manager_when_unknown_data_source_then_value_error_naming_the_airlock():
    # Arrange
    manager = Manager(Principal("u-1", "e@x", frozenset()), AirlockRef("acme", "hr", "r"))

    # Act / Assert
    with pytest.raises(ValueError, match="^acme/hr has no data source crm$"):
        with manager.data_source("crm"):
            pass


async def test_given_handler_naming_a_wrong_llm_or_data_source_when_asked_then_logged_with_its_message_others_by_type(caplog):
    # Arrange
    airlock = dataclasses.replace(AIRLOCK, tools=(), context_preparators=("Broken", "WrongDataSource", "WrongLlm"))
    client, _ = harness(Model("Three months."), airlock=airlock,
                        handlers=[handler(WrongLlm, Stage.CONTEXT_PREPARATOR), handler(Broken, Stage.CONTEXT_PREPARATOR),
                                  handler(WrongDataSource, Stage.CONTEXT_PREPARATOR)])

    # Act
    await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    logged = [r.getMessage() for r in caplog.records if "failed at" in r.getMessage()]
    assert logged == ["handler Broken failed at context_prep with KeyError",
                      "handler WrongDataSource failed at context_prep: acme/hr has no data source crm",
                      "handler WrongLlm failed at context_prep: no model named gemma in this airlock's space or globally"]


async def test_given_verifier_naming_a_wrong_llm_when_answered_then_warn_note_carries_the_message():
    # Arrange
    Notes.seen.clear()
    airlock = dataclasses.replace(AIRLOCK, verifiers=("WrongLlm", "Throws", "Notes"))
    client, _ = harness(Model(Reply("", [tool_call()]), IN_FORMAT), Tools(SALARY), airlock=airlock,
                        handlers=[handler(WrongLlm, Stage.ANSWER_VERIFIER), handler(Throws, Stage.ANSWER_VERIFIER),
                                  handler(Notes, Stage.ANSWER_VERIFIER, order=1)])

    # Act
    await client.post(PATH, json=ASK, headers=GOOD)

    # Assert
    assert Notes.seen == ["verifier failed: ValueError",
                          "verifier failed: no model named gemma in this airlock's space or globally"]


def test_given_manager_when_unknown_llm_then_llm_error_at_once():
    # Arrange
    manager = Manager(Principal("u-1", "e@x", frozenset()), AirlockRef("acme", "hr", "r"), llms={})

    # Act / Assert
    with pytest.raises(LlmError, match="^no model named nomic in this airlock's space or globally$"):
        manager.llm("nomic")
