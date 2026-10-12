import json
import re
from collections.abc import Iterator, Sequence
from typing import Any

from aisolate import AIsolateManager, Answer, Evidence, Verdict, verifier

# A year on its own: the 2025 of "FY2025" and of "2025-10-03", not of 12025. ASCII digits only.
YEAR = re.compile(r"(?<!\d)(19|20)\d\d(?!\d)", re.ASCII)
# A year the question names: "2000 EUR", "2000%" and "$2000" (or with the euro sign) are amounts, not years;
# "2025 European" is a year.
ASKED = re.compile(r"(?<![\d\u20ac$])(19|20)\d\d(?!\d)(?!\s*(?:(?:eur|usd)\b|\u20ac|%|\$))", re.ASCII | re.IGNORECASE)


@verifier(order=1, description="Check the period asked")
class PeriodVerifier:
    """Every figure the answer cites is for a year the question names: the 2024 revenue does not answer what
    was reported for 2025. A figure's year is the first year in its period, so "FY2025", "2025" and
    "2025-10-03" are all 2025. The cells a computation is made of are not judged, and the years they span
    are expected too: "revenue grew in 2025" is computed from 2024 and 2025, and may be shown beside the 2024
    figure. Nothing to check when the question names no year (an amount such as "2000 EUR" is no year), or when no
    cited source has a period with a year."""

    def verify(self, answer: Answer, evidence: Sequence[Evidence], aisolate: AIsolateManager) -> Verdict:
        if not answer.grounding_json:
            return Verdict.pass_("no figure to check")
        asked = sorted({year.group() for year in ASKED.finditer(answer.question)})
        if not asked:
            return Verdict.pass_("the question names no period")
        # Only a tool result can be cited; passages are a JSON list.
        results = {item.id for item in evidence if item.content_json.startswith("{")}
        grounding = json.loads(answer.grounding_json)
        spanned = set(filter(None, (year_of(cell, results) for cell in operands(grounding))))
        expected = spanned.union(asked)
        years = ", ".join(asked)
        judged = False
        for display, source in sources(grounding):
            year = year_of(source, results)
            if year is None:
                continue
            if year not in expected:
                return Verdict.fail(f"the figure {display} is for {source['period']}; the question asks about {years}")
            judged = True
        return Verdict.pass_(f"every figure is for {years}" if judged else "no figure to check")


def year_of(source: dict[str, Any], results: set[str]) -> str | None:
    """The first year in the period of a source cited from a tool result of the exchange; None for any other."""
    found = YEAR.search(source.get("period") or "")
    return found.group() if found and source.get("evidence") in results else None


def operands(grounding: dict[str, Any]) -> Iterator[dict[str, Any]]:
    """The cells the computations of the answer are made of."""
    for figure in grounding["figures"]:
        yield from figure.get("computed", {}).get("of", ())


def sources(grounding: dict[str, Any]) -> Iterator[tuple[str, dict[str, Any]]]:
    """Each source the answer judges, with the display it is shown under: a figure's, its computation's
    ranked rows and row, a table cell's; not the cells a computation is made of. Any status counts: grounding
    keeps the source of a flagged figure, and its flag says nothing of the period. A null is left out of
    the grounding, so a source may be missing."""
    for figure in grounding["figures"]:
        computed = figure.get("computed", {})
        yield figure["display"], figure.get("source", {})
        for ranked in computed.get("rows", ()):
            yield ranked["display"], ranked
        yield figure["display"], computed.get("row", {})
    for table in grounding["tables"]:
        for row in table["rows"]:
            for cell in row:
                yield cell["display"], cell.get("source", {})
