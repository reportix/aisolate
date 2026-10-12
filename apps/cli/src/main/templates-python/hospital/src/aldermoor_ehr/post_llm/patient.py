import json
import re
from collections.abc import Iterable, Iterator, Sequence
from typing import Any

from aisolate import AIsolateManager, Answer, Evidence, Verdict, verifier

# A name's first word and the question's words: letters only, three or more.
WORD = re.compile(r"[^\W\d_]{3,}")


@verifier(order=3, description="Check the patient asked")
class PatientVerifier:
    """Every figure the answer cites is about a patient the question names: Amira's blood pressure does not
    answer what Julian's was. A patient is a value of a tool result's subject column, and the question names
    one when the first word of the name is a word of the question. Nothing to check when the question names
    no patient, or when no cited source of the exchange has a subject."""

    def verify(self, answer: Answer, evidence: Sequence[Evidence], aisolate: AIsolateManager) -> Verdict:
        if not answer.grounding_json:
            return Verdict.pass_("no figure to check")
        # A tool result is a JSON object, the evidence of passages a list.
        results = {e.id: json.loads(e.content_json) for e in evidence if e.content_json.startswith("{")}
        words = set(WORD.findall(answer.question.lower()))
        asked = sorted({name for name in _patients(results.values()) if _first_word(name) in words})
        if not asked:
            return Verdict.pass_("the question names no patient")
        pairs = _sources(json.loads(answer.grounding_json))
        # Only a tool result of this exchange is a record of it, and an aggregate's source has no subject.
        cited = [
            (display, source["subject"])
            for display, source in pairs
            if source.get("evidence") in results and source.get("subject") is not None
        ]
        if not cited:
            return Verdict.pass_("no figure to check")
        names = ", ".join(asked)
        for display, subject in cited:
            if subject not in asked:
                return Verdict.fail(f"the figure {display} is about {subject}; the question asks about {names}")
        return Verdict.pass_(f"every figure is about {names}")


def _patients(results: Iterable[dict[str, Any]]) -> Iterator[str]:
    """The subject-column values of every tool result."""
    for result in results:
        column = (result.get("labels") or {}).get("subject")  # null for a result a tool built itself
        for row in result.get("rows") or ():
            name = row.get(column)
            if isinstance(name, str):
                yield name


def _first_word(name: str) -> str | None:
    """The first word of three letters or more, lower-cased: "Jo Marsh" is "marsh"."""
    word = WORD.search(name.lower())
    return word.group() if word else None


def _sources(grounding: dict[str, Any]) -> Iterator[tuple[str, dict[str, Any]]]:
    """Each source the answer cites, with the display it is shown under: a figure's, its computation's
    operands, ranked rows and row, a table cell's. Any status counts: grounding keeps the source of a
    flagged figure, and its flag says nothing of the patient. A null is left out of the grounding, so a
    source may be missing."""
    for figure in grounding["figures"]:
        computed = figure.get("computed") or {}
        yield figure["display"], figure.get("source") or {}
        for operand in computed.get("of") or ():
            yield operand["display"], operand
        for ranked in computed.get("rows") or ():
            yield ranked["display"], ranked
        yield figure["display"], computed.get("row") or {}
    for table in grounding["tables"]:
        for row in table["rows"]:
            for cell in row:
                yield cell["display"], cell.get("source") or {}
