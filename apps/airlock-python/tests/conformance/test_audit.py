import io
import json
from datetime import datetime
from types import SimpleNamespace

import jsonschema
import pytest

from aisolate_airlock import audit, jsonw
from conftest import conformance_dir, read_case

AUDIT = conformance_dir() / "audit"
SCHEMA = json.loads((AUDIT / "audit-event.schema.json").read_text(encoding="utf-8"))
EXAMPLES = ["exchange.json", "refusal.json", "error.json", "tool-error.json"]
OUTCOMES = read_case("audit", "outcome.json")["cases"]
ROWS = read_case("audit", "rows-hash.json")["cases"]
STRIPS = read_case("audit", "grounding-strip.json")["cases"]
AIRLOCK = SimpleNamespace(space="acme", id="hr", revision="7b2280eab4b5", model="ollama")


def _rebuilt(fields: dict) -> str:
    line = audit._line(datetime.fromisoformat(fields.pop("ts")), *fields.values())
    sink = audit.AuditSink(None, None, None, 0, True)
    out = io.StringIO()
    audit.AuditLog(sink, stdout=True, out=out).record(line)
    return out.getvalue()


@pytest.mark.parametrize("example", EXAMPLES, ids=EXAMPLES)
def test_given_example_line_when_written_by_the_airlock_then_same_bytes_and_valid(example):
    # Arrange
    text = (AUDIT / example).read_text(encoding="utf-8").strip()
    fields = jsonw.loads(text)

    # Act
    written = _rebuilt(dict(fields))

    # Assert
    assert written == text + "\n"
    jsonschema.validate(json.loads(written), SCHEMA)
    assert list(json.loads(written)) == list(SCHEMA["properties"])


@pytest.mark.parametrize("case", OUTCOMES, ids=[c["id"] for c in OUTCOMES])
def test_given_exchange_ending_so_when_its_line_is_built_then_outcome_reason_and_answer_as_java(case):
    # Arrange
    exchange = audit.ExchangeAudit(AIRLOCK, "eric@example.com", "acme-chat", "What is my gross salary?")
    if case["answer"] is not None:
        exchange.accept({"content": case["answer"], "type": "answer_chunk"})
    if case["failure"] is not None:
        exchange.failed_with(case["failure"])

    # Act
    line = exchange.to_line(datetime.now().astimezone(), 5, "4bf92f3577b34da6a3ce929d0e0e4736")

    # Assert
    expect = case["expect"]
    assert (line["outcome"], line["reason"], line["answer"]) == (
        expect["outcome"], expect["reason"], expect["answer_on_the_line"])
    jsonschema.validate(json.loads(jsonw.dumps(line)), SCHEMA)


@pytest.mark.parametrize("case", ROWS, ids=[c["id"] for c in ROWS])
def test_given_tool_rows_when_hashed_then_canonical_text_and_hash_as_java(case):
    # Arrange
    rows = case["rows"]

    # Act
    canonical, hashed = audit.canonical_rows(rows), audit.rows_hash(rows)

    # Assert
    assert (canonical, hashed) == (case["canonical"], case["rows_hash"])


@pytest.mark.parametrize("case", STRIPS, ids=[c["id"] for c in STRIPS])
def test_given_chat_grounding_when_recorded_then_line_keeps_it_without_cell_values(case):
    # Arrange
    line = audit._line(datetime.now().astimezone(), "exchange", None, "acme", "hr", "r", None, None,
                       "q", "a", "answered", None, [], [], [], None, case["grounding"], None, 1)
    out = io.StringIO()

    # Act
    audit.AuditLog(audit.AuditSink(None, None, None, 0, True), out=out).record(line)

    # Assert
    assert json.loads(out.getvalue())["grounding"] == case["expect"]


@pytest.mark.parametrize(("ms", "printed"), [(434, "2026-10-16T20:58:38.434Z"), (100, "2026-10-16T20:58:38.100Z"),
                                             (0, "2026-10-16T20:58:38Z")])
def test_given_instant_when_printed_then_as_java_instant(ms, printed):
    # Arrange
    at = datetime.fromisoformat("2026-10-16T20:58:38+00:00").replace(microsecond=ms * 1000 + 999)

    # Act
    text = audit.instant(at)

    # Assert
    assert text == printed
