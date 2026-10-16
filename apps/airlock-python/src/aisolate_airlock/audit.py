"""The audit trail: one line per exchange and per refusal (``AuditEvent``, ``ExchangeAudit``), on
stdout and handed to the backend's ingest through an ordered on-disk buffer (``AuditLog``,
``AuditSink``); the pod's metrics are counted from the same lines."""

from __future__ import annotations

import hashlib
import logging
import re
import sys
import threading
import time
from datetime import UTC, datetime
from pathlib import Path

import httpx2
from prometheus_client import Counter, Gauge, Histogram

from aisolate_airlock import jsonw, prompts
from aisolate_airlock.answer_format import java_strip
from aisolate_airlock.grounding import number, plain

log = logging.getLogger("aisolate_airlock.audit")

_LEADING_MARKS = re.compile(r"^[ \t\n\x0b\f\r*_>\"'`“‘]+")
_DONT_KNOW = re.compile(r"i don['’]t know(?![a-zA-Z0-9])", re.IGNORECASE | re.ASCII)
_CITATION = re.compile(r"\bev-\d+\b", re.ASCII)


def is_dont_know(answer: str) -> bool:
    """``Answers.isDontKnow``: blank, or begins with "I don't know" after emphasis and quote marks."""
    text = _LEADING_MARKS.sub("", answer, count=1)
    return not java_strip(text) or _DONT_KNOW.match(text) is not None


def instant(at: datetime) -> str:
    """``Instant.toString`` of a time truncated to the millisecond: no fraction when it is 0."""
    at = at.astimezone(UTC)
    ms = at.microsecond // 1000
    return at.strftime("%Y-%m-%dT%H:%M:%S") + (f".{ms:03d}Z" if ms else "Z")


def canonical_rows(rows: list[dict]) -> str:
    """The rows as ``rows_hash`` hashes them: each row's keys sorted, every number as plain decimal text."""
    return jsonw.dumps([{k: plain(n) if (n := number(v)) is not None else v for k, v in row.items()}
                        for row in rows], sort_keys=True)


def rows_hash(rows: list[dict]) -> str:
    """``ChatExchange.rowsHash``: what an auditor re-running the query hashes (docs/verification.md)."""
    return hashlib.sha256(canonical_rows(rows).encode("utf-8")).hexdigest()


def _line(ts, event, trace_id, space, airlock, revision, user, client, question, answer,
          outcome, reason, tools, evidence, verifiers, figures, grounding, model, duration_ms) -> dict:
    return {"ts": instant(ts), "event": event, "trace_id": trace_id, "space": space, "airlock": airlock,
            "revision": revision, "user": user, "client": client, "question": question,
            "answer": answer, "outcome": outcome, "reason": reason, "tools": tools, "evidence": evidence,
            "verifiers": verifiers, "figures": figures, "grounding": grounding, "model": model,
            "duration_ms": duration_ms}


def refusal(ts, trace_id, space, airlock, revision, user, client, question, reason, duration_ms) -> dict:
    return _line(ts, "refusal", trace_id, space, airlock, revision, user, client, question, None,
                 "refused", reason, [], [], [], None, None, None, duration_ms)


_ERROR_CODES = {prompts.ERROR_MODEL_UNAVAILABLE: "llm_unavailable", prompts.ERROR_NO_MODEL_ENDPOINT: "no_model_endpoint"}


class ExchangeAudit:
    """What one exchange's line is made of, gathered from its events and its model calls."""

    def __init__(self, airlock, user: str | None, client: str | None, question: str):
        self.airlock = airlock
        self.user = user
        self.client = client
        self.question = question
        self.tools: dict[str, dict] = {}
        self.arguments: dict[str, dict] = {}
        self.evidence: list[str] = []
        self.referenced: set[str] = set()
        self.verifiers: list[dict] = []
        self.answer: str | None = None
        self.figures: dict | None = None
        self.grounding: dict | None = None
        self.failure: str | None = None
        self.model_name: str | None = None
        self.model_calls = 0
        self.input_tokens: int | None = None
        self.output_tokens: int | None = None

    def accept(self, event: dict) -> None:
        kind = event["type"]
        if kind == "tool_call":
            self.tools[event["id"]] = {"name": event["tool"], "args": None, "data_source": None, "rows": None,
                                       "rule_version": None, "sql_hash": None, "rows_hash": None, "error": None}
            self.arguments[event["id"]] = event["args"]
        elif kind == "evidence":
            self.evidence.append(event["id"])
            tool = self.tools.get(event["call_id"])
            if tool is not None:
                tool.update(args=self.arguments.get(event["call_id"]), data_source=event["data_source"],
                            rows=event["row_count"], rule_version=event["rule_version"],
                            sql_hash=event["sql_hash"], rows_hash=event.get("_rows_hash"))
        elif kind == "tool_error":
            tool = self.tools.get(event["call_id"])
            if tool is not None:
                tool.update(data_source=event.get("_data_source"), error=event["error"])
        elif kind == "answer_chunk":
            self.answer = (self.answer or "") + event["content"]
        elif kind == "verification":
            self.verifiers += [{"name": v["handler"], "verdict": v["status"]} for v in event["verdicts"]]
        elif kind == "error":
            self.failure = self.failure or _ERROR_CODES.get(event["message"], "internal")
        elif kind == "done":
            p = event["provenance"]
            self.figures = {k: p[k] for k in ("sourced", "computed", "unsourced", "mismatch")}
            self.referenced |= _referenced_by(event)
            self.grounding = {k: event[k] for k in ("segments", "figures", "tables", "quotes")}

    def failed_with(self, reason: str) -> None:
        self.failure = reason

    def model_called(self, name: str | None) -> None:
        """Before the call: a failed one counts too."""
        self.model_calls += 1
        self.model_name = name or self.model_name

    def model_answered(self, input_tokens: int | None, output_tokens: int | None) -> None:
        if input_tokens is not None:
            self.input_tokens = (self.input_tokens or 0) + input_tokens
        if output_tokens is not None:
            self.output_tokens = (self.output_tokens or 0) + output_tokens

    def to_line(self, began: datetime, duration_ms: int, trace_id: str | None) -> dict:
        said = self.answer
        if self.failure is not None or said is None:
            outcome = "error"
        else:
            outcome = "dont_know" if is_dont_know(said) else "answered"
        error = outcome == "error"
        a = self.airlock
        return _line(began, "exchange", trace_id, a.space, a.id, a.revision, self.user, self.client,
                     self.question, said, outcome, (self.failure or "internal") if error else None,
                     list(self.tools.values()), self._cited(said), self.verifiers,
                     None if error else self.figures, None if error else self.grounding,
                     {"provider": a.model, "name": self.model_name, "calls": self.model_calls,
                      "input_tokens": self.input_tokens, "output_tokens": self.output_tokens},
                     duration_ms)

    def _cited(self, said: str | None) -> list[str]:
        named = set(_CITATION.findall(said)) if said is not None else set()
        named |= self.referenced
        return [e for e in self.evidence if e in named]


def _referenced_by(done: dict) -> set[str]:
    ids = set()
    for figure in done["figures"]:
        if figure.get("status") in ("sourced", "computed"):
            if figure.get("source"):
                ids.add(figure["source"]["evidence"])
            computed = figure.get("computed") or {}
            ids |= {operand["evidence"] for operand in computed.get("of") or []}
            if computed.get("by"):
                ids.add(computed["by"]["evidence"])
    ids |= {t["evidence"] for t in done["tables"] if t.get("status") == "sourced"}
    ids |= {q["evidence"] for q in done["quotes"] if q.get("status") == "quoted"}
    return ids


_ROW_VALUES = ("value", "subject", "period", "unit")


def strip_row_values(node):
    """``AuditLog.stripRowValues``: what a cell held stays with the rows, at any depth."""
    if isinstance(node, dict):
        return {k: strip_row_values(v) for k, v in node.items() if k not in _ROW_VALUES}
    if isinstance(node, list):
        return [strip_row_values(v) for v in node]
    return node


# Labels in Micrometer's order, alphabetical, so the exposition reads as the Java pod's.
EXCHANGES = Counter("aisolate_exchanges", "Requests to an airlock by outcome", ["airlock", "outcome"])
REFUSALS = Counter("aisolate_refusals", "Refusals with a 403 by reason", ["airlock", "reason"])
TOOL_CALLS = Counter("aisolate_tool_calls", "Tool calls the model made", ["airlock", "tool"])
DURATION = Histogram("aisolate_exchange_duration_seconds", "Exchanges from admission to the last event",
                     ["airlock"], buckets=(0.5, 1, 2, 5, 10, 20, 30, 60, 120, 240, 480))
BUFFERED = Gauge("aisolate_audit_buffered_bytes", "Audit lines not yet handed to the backend")
DROPPED = Counter("aisolate_audit_dropped", "Audit lines dropped")


class AuditSink:
    """Hands every line to the ingest in order, one file per line on the pod's emptyDir; one sender
    thread sends the oldest, retries with backoff, deletes it once taken (``AuditSink.java``)."""

    def __init__(self, url: str | None, token_file: str | None, directory: str | None, max_bytes: int,
                 fail_closed: bool, first_backoff: float = 0.5, max_backoff: float = 30.0, drain: float = 10.0):
        self.url = url or None
        self.token_file = Path(token_file) if token_file else None
        self.dir = Path(directory) if directory else None
        self.max_bytes = max_bytes
        self.fail_closed = fail_closed
        self.first_backoff = first_backoff
        self.max_backoff = max_backoff
        self.drain = drain
        self.bytes = 0
        self._next = 0
        self._lock = threading.Lock()
        self._wake = threading.Event()
        self._closing = False
        self._sender: threading.Thread | None = None

    @property
    def enabled(self) -> bool:
        return self.url is not None

    def start(self) -> None:
        if not self.enabled:
            return
        self.dir.mkdir(parents=True, exist_ok=True)
        for file in self.dir.iterdir():
            if file.name.endswith(".tmp"):
                file.unlink()  # a write that never finished: its exchange never answered either
            elif file.name.endswith(".json"):
                self._next = max(self._next, int(file.name[: -len(".json")]) + 1)
                self.bytes += file.stat().st_size
        BUFFERED.set(self.bytes)
        self._sender = threading.Thread(target=self._run, name="audit-sink", daemon=True)
        self._sender.start()

    def accepting(self) -> bool:
        return not self.enabled or not self.fail_closed or self.bytes < self.max_bytes

    def offer(self, line: str) -> bool:
        if not self.enabled:
            return True
        with self._lock:
            if not self.fail_closed and self.bytes >= self.max_bytes:
                return False
            data = line.encode("utf-8")
            sequence = self._next
            self._next += 1
            tmp = self.dir / f"{sequence}.tmp"
            try:
                tmp.write_bytes(data)
                tmp.replace(self.dir / f"{sequence:019d}.json")
            except OSError as e:
                log.error("audit line %d could not be buffered: %s", sequence, type(e).__name__)
                return False
            self.bytes += len(data)
            BUFFERED.set(self.bytes)
        self._wake.set()
        return True

    def close(self) -> None:
        if self._sender is None:
            return
        self._closing = True
        self._wake.set()
        self._sender.join(self.drain)
        if self.bytes > 0:
            log.error("audit trail: %d bytes of lines not handed over at shutdown", self.bytes)

    def _oldest(self) -> Path | None:
        # ponytail: lists the directory per send; an index in memory if buffers grow to many thousands.
        files = sorted(f for f in self.dir.iterdir() if f.name.endswith(".json"))
        return files[0] if files else None

    def _run(self) -> None:
        backoff = self.first_backoff
        with httpx2.Client(timeout=httpx2.Timeout(10.0, connect=5.0)) as http:
            while True:
                try:
                    file = self._oldest()
                    if file is None:
                        if self._closing:
                            return
                        self._wake.wait(1.0)
                        self._wake.clear()
                        continue
                    delivery = self._deliver(http, file)
                    if delivery == "retry":
                        if self._closing:
                            return
                        time.sleep(backoff)
                        backoff = min(backoff * 2, self.max_backoff)
                        continue
                    size = file.stat().st_size
                    file.unlink()
                    with self._lock:
                        self.bytes -= size
                        BUFFERED.set(self.bytes)
                    backoff = self.first_backoff
                except OSError:
                    log.exception("audit sink stopped: its buffer cannot be read")
                    return

    def _deliver(self, http: httpx2.Client, file: Path) -> str:
        try:
            token = self.token_file.read_text(encoding="utf-8").strip()
            response = http.post(self.url, content=file.read_bytes(),
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
        except (httpx2.HTTPError, OSError) as e:
            log.warning("audit ingest unreachable (%s); retrying", type(e).__name__)
            return "retry"
        if response.status_code in (200, 201):
            return "taken"
        if response.status_code == 400:
            # Never acceptable, so never retried: it would hold back every line after it.
            log.error("audit line %s refused by the backend: %s", file.name, response.text)
            return "refused"
        log.warning("audit ingest answered %d; retrying", response.status_code)
        return "retry"


class AuditLog:
    """Each line to stdout (unless off; always without an ingest) and to the sink; metrics from it."""

    def __init__(self, sink: AuditSink, stdout: bool = True, out=None):
        self.sink = sink
        self.stdout = stdout or not sink.enabled
        self.out = out or sys.stdout
        self._lock = threading.Lock()

    def accepting(self) -> bool:
        return self.sink.accepting()

    def record(self, line: dict) -> None:
        """Never raises: the exchange is over, and a line that cannot be written must not undo it."""
        try:
            if line.get("grounding") is not None:
                line = {**line, "grounding": strip_row_values(line["grounding"])}
            text = jsonw.dumps(line)
            if self.stdout:
                with self._lock:
                    self.out.write(text + "\n")
                    self.out.flush()
            if not self.sink.offer(text):
                DROPPED.inc()
                log.error("audit line for %s/%s dropped: the buffer is full", line["space"], line["airlock"])
        except Exception as e:  # noqa: BLE001 - the pod's log names the request, never what was said
            log.error("audit line for %s/%s could not be written: %s", line.get("space"), line.get("airlock"),
                      type(e).__name__)
        self._count(line)

    @staticmethod
    def _count(line: dict) -> None:
        airlock = line["airlock"]
        EXCHANGES.labels(airlock=airlock, outcome=line["outcome"]).inc()
        if line["event"] == "refusal":
            REFUSALS.labels(airlock=airlock, reason=line["reason"]).inc()
            return
        for tool in line["tools"]:
            TOOL_CALLS.labels(airlock=airlock, tool=tool["name"]).inc()
        DURATION.labels(airlock).observe(line["duration_ms"] / 1000)
