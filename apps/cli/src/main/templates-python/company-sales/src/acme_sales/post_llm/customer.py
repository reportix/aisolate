import json
import re
from collections.abc import Iterator, Sequence
from typing import Any

from aisolate import AIsolateManager, Answer, Evidence, Verdict, verifier

# A name's first word and the question's words: letters only, three or more.
WORD = re.compile(r"[^\W\d_]{3,}")


@verifier(order=1, description="Check the customer asked")
class CustomerVerifier:
    """Every figure the answer cites is about a customer the question names: Northwind's invoice does not
    answer what Fabrikam owes. A customer is a value of a tool result's subject column, and the question
    names it when the first word of its name is a word of the question. Nothing to check when the question
    names none, or when no cited source has a subject."""

    def verify(self, answer: Answer, evidence: Sequence[Evidence], aisolate: AIsolateManager) -> Verdict:
        if not answer.grounding_json:
            return Verdict.pass_("no figure to check")
        words = set(WORD.findall(answer.question.lower()))
        asked = sorted({name for name in customers(evidence) if first_word(name) in words})
        if not asked:
            return Verdict.pass_("the question names no customer")
        pairs = sources(json.loads(answer.grounding_json))
        cited = [(display, source["subject"]) for display, source in pairs if source.get("subject") is not None]
        if not cited:
            return Verdict.pass_("no figure to check")
        names = ", ".join(asked)
        for display, subject in cited:
            if subject not in asked:
                return Verdict.fail(f"the figure {display} is about {subject}; the question asks about {names}")
        return Verdict.pass_(f"every figure is about {names}")


def customers(evidence: Sequence[Evidence]) -> Iterator[str]:
    """The subject-column values of every tool result; passages are a JSON list, no result."""
    for item in evidence:
        if item.content_json.startswith("{"):
            result = json.loads(item.content_json)
            column = (result.get("labels") or {}).get("subject")  # null for a result a tool built itself
            for row in result.get("rows") or ():
                name = row.get(column)
                if isinstance(name, str):
                    yield name


def first_word(name: str) -> str | None:
    """The first word of three letters or more, lower-cased: "AG Holdings" is "holdings"."""
    word = WORD.search(name.lower())
    return word.group() if word else None


def sources(grounding: dict[str, Any]) -> Iterator[tuple[str, dict[str, Any]]]:
    """Each source the answer cites, with the display it is shown under: a figure's, its computation's
    operands, ranked rows and row, a table cell's. Any status counts: grounding keeps the source of a
    flagged figure, and its flag says nothing of the customer. A null is left out of the grounding, so a
    source may be missing."""
    for figure in grounding["figures"]:
        computed = figure.get("computed", {})
        yield figure["display"], figure.get("source", {})
        for operand in computed.get("of", ()):
            yield operand["display"], operand
        for ranked in computed.get("rows", ()):
            yield ranked["display"], ranked
        yield figure["display"], computed.get("row", {})
    for table in grounding["tables"]:
        for row in table["rows"]:
            for cell in row:
                yield cell["display"], cell.get("source", {})
