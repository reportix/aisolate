"""One chat exchange (``ChatExchange``, ``AgentLoopService``, ``HandlerChain``): the prompt, the handlers
around the model, the tool-calling loop and its budget, the answer in the format and its grounding, the
events the browser sees and the audit line every outcome passes through."""

from __future__ import annotations

import json
import logging
import re
import time
from collections.abc import Callable
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime

import anyio
from aisolate import AirlockRef, Answer, Evidence, LlmError, Passage, Principal, Status, Verdict
from aisolate.build import Handler
from opentelemetry import trace

from aisolate_airlock import answer_format, audit, jsonw, prompts, tracing
from aisolate_airlock.answer_format import java_strip
from aisolate_airlock.evidence import ToolEvidence
from aisolate_airlock.grounding import Call, GroundedAnswer, Grounding, Passages, Rows
from aisolate_airlock.grounding import number as grounding_number
from aisolate_airlock.llm import ModelUnavailable
from aisolate_airlock.mcp_client import ToolCallFailedException

log = logging.getLogger("aisolate_airlock.exchange")

MAX_TOOL_CALLS_PER_EXCHANGE = 3
_CONTEXT_TAG = re.compile(r"<\s*/?\s*context\s*>", re.IGNORECASE | re.ASCII)

COULD_NOT_COMPLETE = {"message": prompts.ERROR_COULD_NOT_COMPLETE, "type": "error"}
NO_MODEL_ENDPOINT = {"message": prompts.ERROR_NO_MODEL_ENDPOINT, "type": "error"}
MODEL_UNAVAILABLE = {"message": prompts.ERROR_MODEL_UNAVAILABLE, "type": "error"}


@dataclass(frozen=True)
class Airlock:
    """One airlock as the runtime needs it (``AirlockDefinition``)."""

    space: str
    id: str
    revision: str
    model: str
    roles: tuple[str, ...]
    callers: tuple[str, ...]
    tools: tuple[str, ...]
    data_sources: tuple[str, ...]
    system_prompt: str
    context_preparators: tuple[str, ...] = ()
    memories: tuple[str, ...] = ()
    verifiers: tuple[str, ...] = ()
    description: str = ""
    clients: tuple[str, ...] = ()
    """Machine clients admitted by azp alone, without an e-mail."""

    @property
    def pin(self) -> dict:
        return {"airlock": self.id, "revision": self.revision}


_WORDS = {"contextPreparator": "context preparator", "conversationMemory": "memory", "answerVerifier": "verifier"}


def resolve_handlers(candidates: list[Handler], airlock: Airlock) -> tuple[list[Handler], ...]:
    """``HandlerChain.resolve``: the listed handlers per stage, each exactly one of the code's, by order then name."""

    def select(stage: str, names) -> list[Handler]:
        chosen = []
        for name in names:
            named = [c for c in candidates if c.stage == stage and c.name == name]
            if not named:
                raise RuntimeError(f"no {_WORDS[stage]} {name} in the code of {airlock.space}/{airlock.id}")
            if len(named) > 1:
                raise RuntimeError(f"handler {name} is declared twice")
            chosen.append(named[0])
        return sorted(chosen, key=lambda h: (h.order, h.name))

    return (select("contextPreparator", airlock.context_preparators),
            select("conversationMemory", airlock.memories),
            select("answerVerifier", airlock.verifiers))


class DeadlineExceeded(Exception):
    pass


class IllegalStateException(RuntimeError):
    """Named as Java's: a verifier that adds evidence gets the WARN note ``verifier failed: IllegalStateException``."""


class ExchangeFailed(Exception):
    def __init__(self, error: dict):
        super().__init__(error["message"])
        self.error = error


@dataclass(frozen=True)
class _Llm:
    """The SDK's ``Llm``: one catalog entry, its tokens counted under the exchange's airlock."""

    client: object
    airlock: str

    def embed(self, text):
        return self.client.embed(text, self.airlock)

    def chat(self, prompt: str) -> str:
        return self.client.ask(prompt, self.airlock)


class NoDataSource(ValueError):
    """``data_source(id)`` with an id the airlock lacks."""


# Wrong names in code: their messages hold no data, so they are logged whole; any other exception by type only.
_PLAIN = (LlmError, NoDataSource)


def _why(failed: Exception) -> str:
    return f": {failed}" if isinstance(failed, _PLAIN) else f" with {type(failed).__name__}"


class Manager:
    """``AIsolateManager`` for one exchange: no token, no key, no credential."""

    def __init__(self, principal: Principal, airlock: AirlockRef, pools=None, llms=None):
        self._principal = principal
        self._airlock = airlock
        self._pools = pools
        self._llms = llms or {}

    @property
    def principal(self) -> Principal:
        return self._principal

    @property
    def airlock(self) -> AirlockRef:
        return self._airlock

    def llm(self, id: str) -> _Llm:
        if id not in self._llms:
            raise LlmError(f"no model named {id} in this airlock's space or globally")
        return _Llm(self._llms[id], self._airlock.airlock)

    @contextmanager
    def data_source(self, id: str):
        if self._pools is None or id is None or id not in self._pools.ids:
            raise NoDataSource(f"{self._airlock.space}/{self._airlock.airlock} has no data source {id}")
        span = trace.get_current_span()
        if (getattr(span, "attributes", None) or {}).get("aisolate.handler") is not None:
            span.set_attribute("aisolate.data_source", id)
        with self._pools.connect(id) as connection:
            yield connection

    @property
    def log(self) -> logging.Logger:
        return logging.getLogger("aisolate.handlers")


def _java_list(items) -> str:
    return "[" + ", ".join(items) + "]"


def _declared(input_schema: str) -> set[str]:
    try:
        return set((json.loads(input_schema).get("properties") or {}).keys())
    except (ValueError, AttributeError):
        return set()


def applied(sent: dict, declared: set[str], rows: int) -> dict:
    """``ChatExchange.applied``: the arguments a caption shows, those the schema declares and the query applied."""
    shown = {}
    for name, value in sent.items():
        empty = value is None or (isinstance(value, str) and not java_strip(value)) or (
            isinstance(value, (list, dict)) and not value)
        number = grounding_number(value)
        unreached = name == "limit" and number is not None and int(number) > rows
        if name in declared and not empty and not unreached:
            shown[name] = value
    return shown


class ChatExchange:
    """The SDK's ``Exchange`` for the handlers, and everything AIsolate does around the model."""

    def __init__(self, airlock: Airlock, tool_server, model, emit: Callable[[dict], None], deadline: float,
                 bearer: str, audit_: audit.ExchangeAudit):
        self.airlock = airlock
        self.tool_server = tool_server
        self.model = model
        self.emit = emit
        self.deadline = deadline
        self.bearer = bearer
        self.audit = audit_
        self.offered: list = []
        self.declared: dict[str, set[str]] = {}
        self.conversation: list[dict] = []
        self.browser_evidence: list[dict] = []
        self._question = ""
        self.contexts: list[str] = []
        self.passage_blocks: list[str] = []
        self.retained: dict[str, Evidence] = {}
        self.citable_rows: dict[str, Rows] = {}
        self.citable_passages: dict[str, Passages] = {}
        self.row_handles = 0
        self.tool_results_for_model: dict[str, str] = {}
        self.tool_failures = 0
        # Failed calls whose data source refused the login or could not be reached.
        self.data_failures = 0
        self.tool_calls_attempted = 0
        # What each call that returned rows returned, by tool and arguments: a repeat is answered from it.
        self.answered_calls: dict[str, str] = {}
        self.passages_added = 0
        self.sealed = False

    # ── the SDK's Exchange ──

    @property
    def question(self) -> str:
        return self._question

    def add_evidence(self, source: str, passages) -> str | None:
        self._require_context_stage()
        if not passages:
            return None
        evidence_id = f"ev-{len(self.browser_evidence) + 1}"
        block = "".join(f"\n\n[{evidence_id}, passage {i}] {p.title}, {p.section}\n{p.body}"
                        for i, p in enumerate(passages, 1))
        content_json = jsonw.dumps([{"title": p.title, "section": p.section, "body": p.body} for p in passages])
        for_browser = {"id": evidence_id, "call_id": None, "tool": source, "entity": source,
                       "row_count": len(passages), "columns": ["title", "section"], "rule_version": "",
                       "rules": [], "sql_hash": "", "data_source": None, "type": "evidence"}
        self.browser_evidence.append(for_browser)
        self.retained[evidence_id] = Evidence(evidence_id, source, content_json)
        self.citable_passages[evidence_id] = Passages(evidence_id, [Passage(p.title, p.section, p.body)
                                                                    for p in passages])
        self.passage_blocks.append(block)
        self.passages_added += len(passages)
        self.emit(for_browser)
        return evidence_id

    def add_context(self, text: str) -> None:
        self._require_context_stage()
        if text is not None and java_strip(text):
            self.contexts.append(java_strip(text))

    @property
    def evidence(self) -> list[Evidence]:
        return list(self.retained.values())

    def _require_context_stage(self) -> None:
        if self.sealed:
            raise IllegalStateException("the exchange accepts evidence and context only during context preparation"
                               " (prepare and recall)")

    # ── the conversation ──

    async def open(self, message: str, handlers, manager) -> list[dict]:
        self._question = message
        if self.airlock.tools:
            for spec in await self.tool_server.list_tools(self.bearer, self.airlock.pin):
                if spec.name in self.airlock.tools:
                    self.offered.append(spec)
                    self.declared[spec.name] = _declared(spec.input_schema)
        preparators, memories, _ = handlers
        for handler in preparators:
            await self._run_handler(handler, "context_prep", tracing.PRE_LLM,
                                    lambda h=handler: h.instance.prepare(self, manager))
        for handler in memories:
            await self._run_handler(handler, "memory_recall", tracing.PRE_LLM,
                                    lambda h=handler: h.instance.recall(self, manager))
        self.sealed = True
        self.conversation.append({"role": "system", "content": prompts.PLATFORM_PREAMBLE + "\n\n"
                                  + self.airlock.system_prompt + "\n\n" + prompts.ANSWER_FORMAT_INSTRUCTION})
        context = ""
        if self.contexts:
            context += "\n\nContext for this question:" + "".join("\n\n" + t for t in self.contexts)
        if self.passage_blocks:
            context += "\n\nDocuments for this question, each under its evidence id:" + "".join(self.passage_blocks)
        if context:
            # A document holding the delimiter would close the block, its rest read as the user's words.
            self.conversation.append({"role": "user",
                                      "content": "<context>" + _CONTEXT_TAG.sub("[context]", context) + "\n</context>"})
        self.conversation.append({"role": "user", "content": message})
        return self._within_budget()

    async def _run_handler(self, handler: Handler, stage: str, phase: str, call) -> None:
        with tracing.span(_span_name(handler), {**tracing.describe(self.airlock), "aisolate.handler": handler.name,
                                                "aisolate.stage": phase}) as span:
            before = self.passages_added
            started = time.monotonic()
            try:
                await anyio.to_thread.run_sync(call)
                log.info("handler_ran stage=%s name=%s ms=%d", stage, handler.name, _ms(started))
            except Exception as failed:  # noqa: BLE001 - a developer's handler never fails the exchange
                log.warning("handler %s failed at %s%s", handler.name, stage, _why(failed))
            if phase == tracing.PRE_LLM:
                tracing.tag(span, "aisolate.passages", self.passages_added - before)

    def answered_without_tools(self) -> bool:
        return bool(self.offered) and self.tool_calls_attempted == 0 and not self.browser_evidence

    def nudge_towards_tools(self, reply: str) -> list[dict]:
        self.conversation.append({"role": "assistant", "content": reply or ""})
        self.conversation.append({"role": "user", "content": prompts.USE_TOOLS_NUDGE})
        return self._within_budget()

    def answer_from_evidence(self) -> list[dict]:
        text = prompts.ANSWER_NOW + "".join(f"\n\n[{i}] {r}" for i, r in self.tool_results_for_model.items())
        self.conversation.append({"role": "user", "content": text})
        return self._within_budget()

    # ── the model and its tools ──

    async def _model(self, messages: list[dict], tools=None, schema=None):
        self._within_budget()
        self.audit.model_called(self.model.model)
        reply = await self.model.chat(messages, tools=tools, schema=schema,
                                      airlock=self.airlock.id)
        self.audit.model_answered(reply.input_tokens, reply.output_tokens)
        return reply

    async def ask(self, conversation: list[dict], with_tools: bool = True, schema: str | None = None
                  ) -> tuple[str, bool]:
        """The model and its tool rounds; the rounds stay out of the conversation, as Spring AI's advisor
        keeps them. A turn without tools still has each call it asks for answered and counted. Returns the
        reply and whether the budget of tool calls ran out."""
        offered = [{"name": s.name, "description": s.description, "input_schema": s.input_schema}
                   for s in self.offered] if with_tools else []
        names = {s.name for s in self.offered} if with_tools else set()
        messages = list(conversation)
        calls = 0
        while True:
            reply = await self._model(messages, tools=offered or None, schema=schema)
            if not reply.tool_calls:
                return reply.text, False
            messages.append({"role": "assistant", "content": reply.text, "tool_calls": reply.tool_calls})
            for call in reply.tool_calls:
                calls += 1
                if calls > MAX_TOOL_CALLS_PER_EXCHANGE:
                    return reply.text, True
                function = call.get("function") or {}
                name = function.get("name") or ""
                if name in names:
                    result = await self.run_tool(name, function.get("arguments"))
                else:
                    # Gate 3: a name the airlock does not own is answered, never run.
                    self.tool_calls_attempted += 1
                    result = f"Tool not available: {name}"
                messages.append({"role": "tool", "content": result, "tool_name": name})

    async def run_tool(self, name: str, arguments) -> str:
        try:
            args = _arguments(arguments)
        except ValueError:
            self.tool_calls_attempted += 1
            self.tool_failures += 1
            return "Error: arguments must be a JSON object"
        key = name + json.dumps(args, sort_keys=True)
        if key in self.answered_calls:
            # A small model asks again for what it already has: answered from the first call, never run twice.
            return prompts.REPEATED_CALL % name + "\n\n" + self.answered_calls[key]
        self.tool_calls_attempted += 1
        call_id = f"call-{self.tool_calls_attempted}"
        self.emit({"id": call_id, "tool": name, "args": args, "type": "tool_call"})
        with tracing.span(f"tool {name}", {**tracing.describe(self.airlock), "aisolate.tool": name}) as span:
            started = time.monotonic()
            try:
                text = await self.tool_server.call_tool(name, args, self.bearer, self.airlock.pin)
            except ToolCallFailedException as failed:
                self.tool_failures += 1
                self.data_failures += failed.data_unreadable
                tracing.failed(span, failed)
                self.emit({"call_id": call_id, "tool": name, "error": failed.error, "type": "tool_error",
                           "_data_source": failed.data_source})
                return f"Error: {failed}"
            try:
                returned = ToolEvidence.parse(text)
            except ValueError:
                # Never quoted: the parser's message holds the text it choked on, and that holds rows.
                self.tool_failures += 1
                self.emit({"call_id": call_id, "tool": name, "error": "failed", "type": "tool_error",
                           "_data_source": None})
                return "Error: the tool server returned an unreadable result"
            evidence_id = f"ev-{len(self.browser_evidence) + 1}"
            for_browser = {"id": evidence_id, "call_id": call_id, "tool": name, "entity": returned.entity,
                           "row_count": returned.row_count, "columns": returned.columns,
                           "rule_version": returned.rule_version, "rules": returned.rules,
                           "sql_hash": returned.sql_hash, "data_source": returned.data_source, "type": "evidence",
                           "_rows_hash": audit.rows_hash(returned.rows)}
            self.browser_evidence.append(for_browser)
            self.emit(for_browser)
            self.retained[evidence_id] = Evidence(evidence_id, name, text)
            by_handle, shown_rows = {}, []
            for row in returned.rows:
                self.row_handles += 1
                handle = f"r{self.row_handles}"
                by_handle[handle] = row
                shown_rows.append({"row": handle, **row})
            self.citable_rows[evidence_id] = Rows(
                evidence_id, Call(name, applied(args, self.declared.get(name, set()), returned.row_count)),
                returned.entity, returned.columns, returned.labels, by_handle)
            tracing.tag(span, "aisolate.rows", returned.row_count)
            log.info("tool_called tool=%s rows=%d evidence=%s ms=%d", name, returned.row_count, evidence_id,
                     _ms(started))
            if self._out_of_time():
                raise DeadlineExceeded()
            shown = jsonw.dumps({"evidence_id": evidence_id, "row_count": returned.row_count, "rows": shown_rows})
            self.tool_results_for_model[evidence_id] = shown
            self.answered_calls[key] = shown
            return shown

    # ── the answer ──

    async def ground(self, draft: str) -> GroundedAnswer:
        if self._must_deny():
            unreadable = not self.browser_evidence and self.data_failures == self.tool_failures
            return GroundedAnswer.plain(prompts.COULD_NOT_REACH_DATA if unreadable else prompts.I_DONT_KNOW)
        said = draft or ""
        grounding = Grounding(list(self.citable_rows.values()), list(self.citable_passages.values()),
                              self._question)
        grounded = _ground_in_format(grounding, said)
        if grounded is not None:
            return grounded
        as_written = grounding.raw(said)
        from_rows = any(rows.by_handle for rows in self.citable_rows.values())
        if not as_written.figures and not from_rows:
            return as_written
        self.conversation.append({"role": "assistant", "content": said})
        self.conversation.append({"role": "user", "content": prompts.ANSWER_IN_FORMAT + "".join(
            f"\n\n[{i}] {r}" for i, r in self.tool_results_for_model.items())})
        formatted = await self._in_format()
        grounded = _ground_in_format(grounding, formatted)
        if grounded is not None:
            return grounded
        self.conversation.append({"role": "assistant", "content": formatted or ""})
        self.conversation.append({"role": "user", "content": prompts.RETRY % _format_problem(formatted)})
        grounded = _ground_in_format(grounding, await self._in_format())
        if grounded is not None:
            return grounded
        log.info("answer_format airlock=%s/%s not followed twice", self.airlock.space, self.airlock.id)
        return as_written

    async def _in_format(self) -> str:
        text, _ = await self.ask(self._within_budget(), with_tools=False, schema=prompts.ANSWER_FORMAT_SCHEMA)
        return text

    async def answer(self, grounded: GroundedAnswer, handlers, manager) -> None:
        _, memories, verifiers = handlers
        text = grounded.answer
        evidence_ids = [e["id"] for e in self.browser_evidence]
        self.emit({"content": text, "type": "answer_chunk"})
        grounding_json = jsonw.dumps(grounded.to_json())
        verdicts = await self._verify(text, grounding_json, verifiers, manager)
        if verifiers:
            self.emit({"verdicts": [{"handler": v.handler, "status": str(v.status), "note": v.note}
                                    for v in verdicts], "type": "verification"})
        self.emit({"evidence_ids": evidence_ids, "grounded": bool(self.browser_evidence), **grounded.to_json(),
                   "type": "done"})
        answer = Answer(text, tuple(verdicts), self._question, grounding_json)
        for handler in memories:
            await self._run_handler(handler, "memory_remember", tracing.POST_LLM,
                                    lambda h=handler: h.instance.remember(self, answer, manager))

    async def _verify(self, text: str, grounding_json: str, verifiers: list[Handler], manager) -> list[Verdict]:
        verdicts: list[Verdict] = []
        for handler in verifiers:
            with tracing.span(_span_name(handler), {**tracing.describe(self.airlock), "aisolate.handler": handler.name,
                                     "aisolate.stage": tracing.POST_LLM}) as span:
                started = time.monotonic()
                answer = Answer(text, tuple(verdicts), self._question, grounding_json)
                try:
                    returned = await anyio.to_thread.run_sync(
                        lambda h=handler, a=answer: h.instance.verify(a, self.evidence, manager))
                    if returned is None:
                        verdict = Verdict(Status.WARN, "returned no verdict", handler.name)
                    elif getattr(returned, "status", None) is None:
                        verdict = Verdict(Status.WARN, "returned no status", handler.name)
                    else:
                        verdict = Verdict(returned.status, returned.note or "", handler.name)
                except Exception as failed:  # noqa: BLE001 - a WARN verdict under the verifier's own name
                    log.warning("verifier %s failed%s", handler.name, _why(failed))
                    note = str(failed) if isinstance(failed, _PLAIN) else type(failed).__name__
                    verdict = Verdict(Status.WARN, f"verifier failed: {note}", handler.name)
                log.info("verifier_ran name=%s status=%s ms=%d", handler.name, verdict.status, _ms(started))
                tracing.tag(span, "aisolate.verdict", verdict.status)
                verdicts.append(verdict)
        return verdicts

    def give_up(self) -> None:
        self.emit(COULD_NOT_COMPLETE)

    def _must_deny(self) -> bool:
        grounded = bool(self.browser_evidence)
        every_result_empty = grounded and all(e["row_count"] == 0 for e in self.browser_evidence)
        return every_result_empty or (self.tool_failures > 0 and not grounded)

    def _within_budget(self) -> list[dict]:
        if self._out_of_time():
            raise DeadlineExceeded()
        return list(self.conversation)

    def _out_of_time(self) -> bool:
        return time.monotonic() >= self.deadline


def _arguments(arguments) -> dict:
    if isinstance(arguments, dict):
        return arguments
    if arguments is None or (isinstance(arguments, str) and not java_strip(arguments)):
        return {}
    if isinstance(arguments, str):
        parsed = json.loads(arguments)
        if isinstance(parsed, dict):
            return parsed
    raise ValueError("arguments are not a JSON object")


def _ground_in_format(grounding: Grounding, said: str | None) -> GroundedAnswer | None:
    try:
        return grounding.ground(answer_format.parse(said))
    except ValueError:
        return None


def _format_problem(said: str | None) -> str:
    try:
        answer_format.parse(said)
        return "unknown"
    except ValueError as problem:
        return str(problem)


def _span_name(handler: Handler) -> str:
    return handler.description if handler.description and java_strip(handler.description) else handler.name


def _ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


class AgentLoop:
    """``AgentLoopService``: which model answers, the push towards the tools, the budget, the audit line."""

    def __init__(self, model, tool_server, handlers: list[Handler], audit_log: audit.AuditLog,
                 max_duration: float = 480.0):
        self.model = model
        self.tool_server = tool_server
        self.handlers = handlers
        self.audit_log = audit_log
        self.max_duration = max_duration

    async def answer_user_message(self, airlock: Airlock, message: str, manager, client: str | None,
                                  emit: Callable[[dict], None], bearer: str, exchange_span=None) -> None:
        began = datetime.now(UTC)
        started = time.monotonic()
        exchange_audit = audit.ExchangeAudit(airlock, manager.principal.email, client, message)

        def watched(event: dict) -> None:
            exchange_audit.accept(event)
            emit(event)

        try:
            await self._run(airlock, message, manager, exchange_audit, started, watched, bearer)
        finally:
            line = exchange_audit.to_line(began, _ms(started), tracing.trace_id(exchange_span))
            if exchange_span is not None:
                tracing.tag(exchange_span, "aisolate.outcome", line["outcome"])
                for key, value in (line["figures"] or {}).items():
                    tracing.tag(exchange_span, f"aisolate.figures.{key}", value)
            self.audit_log.record(line)

    async def _run(self, airlock, message, manager, exchange_audit, started, emit, bearer) -> None:
        if self.model is None or self.model.provider != airlock.model:
            emit(NO_MODEL_ENDPOINT)
            return
        deadline = time.monotonic() + self.max_duration
        try:
            handlers = resolve_handlers(self.handlers, airlock)
        except Exception as failed:  # noqa: BLE001 - fail closed: a broken airlock answers nothing
            log.warning("handlers of %s/%s could not be loaded: %s", airlock.space, airlock.id, failed)
            emit(COULD_NOT_COMPLETE)
            return
        log.info("exchange_start airlock=%s/%s revision=%s tools=%s data_sources=%s", airlock.space, airlock.id,
                 airlock.revision, _java_list(airlock.tools), _java_list(airlock.data_sources))
        log.info("handlers_resolved airlock=%s/%s context_prep=%s memory=%s verifier=%s", airlock.space,
                 airlock.id, *(_java_list(h.name for h in stage) for stage in handlers))
        exchange = ChatExchange(airlock, self.tool_server, self.model, emit, deadline, bearer, exchange_audit)
        try:
            reply, ran_out = await exchange.ask(await exchange.open(message, handlers, manager))
            if exchange.answered_without_tools():
                reply, ran_out = await exchange.ask(exchange.nudge_towards_tools(reply))
            if ran_out:
                reply, ran_out = await exchange.ask(exchange.answer_from_evidence(), with_tools=False)
            if ran_out:
                exchange.give_up()
            else:
                await exchange.answer(await exchange.ground(reply), handlers, manager)
            log.info("exchange_done airlock=%s/%s ms=%d", airlock.space, airlock.id, _ms(started))
        except DeadlineExceeded:
            exchange_audit.failed_with("deadline")
            exchange.give_up()
        except ModelUnavailable as failed:
            log.warning("model endpoint %s failed with %s", airlock.model, failed)
            emit(MODEL_UNAVAILABLE)
        except Exception:  # noqa: BLE001 - the stream is open: a generic error event, the cause in the log
            log.exception("exchange failed for %s/%s", airlock.space, airlock.id)
            exchange.give_up()
        except BaseException:
            # A cancellation (shutdown): the stream still ends with an error, as Java's before it rethrows.
            exchange.give_up()
            raise

    async def collect_answer(self, airlock, message, manager, client, bearer, exchange_span=None) -> dict:
        events: list[dict] = []
        await self.answer_user_message(airlock, message, manager, client, events.append, bearer, exchange_span)
        return chat_answer(events, airlock.revision, tracing.trace_id(exchange_span))


def chat_answer(events: list[dict], revision: str, trace_id: str | None) -> dict:
    """``ChatAnswer.from``: the exchange as one document, twelve fields in this order."""
    answer, evidence, tool_errors, verdicts, done = "", [], [], [], None
    for event in events:
        kind = event["type"]
        if kind == "evidence":
            evidence.append({"id": event["id"], "source": event["tool"], "name": event["entity"],
                             "rows": event["row_count"], "data_source": event["data_source"]})
        elif kind == "tool_error":
            tool_errors.append({"call_id": event["call_id"], "tool": event["tool"], "error": event["error"]})
        elif kind == "answer_chunk":
            answer += event["content"]
        elif kind == "verification":
            verdicts += [{"name": v["handler"], "verdict": v["status"]} for v in event["verdicts"]]
        elif kind == "error":
            raise ExchangeFailed(event)
        elif kind == "done":
            done = event
    grounded = (GroundedAnswer.plain(answer).to_json() if done is None or done.get("provenance") is None
                else done)
    return {"answer": answer, "dont_know": audit.is_dont_know(answer), "segments": grounded["segments"],
            "figures": grounded["figures"], "tables": grounded["tables"], "quotes": grounded["quotes"],
            "provenance": grounded["provenance"], "evidence": evidence, "tool_errors": tool_errors,
            "verification": {"verified": bool(verdicts) and all(v["verdict"] == "PASS" for v in verdicts),
                             "verdicts": verdicts},
            "revision": revision, "trace_id": trace_id}
