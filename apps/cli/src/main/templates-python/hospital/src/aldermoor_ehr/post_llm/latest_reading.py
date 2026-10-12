import json
import re
from collections.abc import Sequence
from typing import Any

from aisolate import AIsolateManager, Answer, Evidence, Verdict, verifier

# A question for a reading by its place in time, not by its date: "last Thursday", "last night" and "at last" are
# dates and a sigh, no ask for the latest.
LATEST = re.compile(
    r"\b(?:(?<!\bat )last(?!\s+(?:night|evening|morning|afternoon|week|month|year"
    r"|monday|tuesday|wednesday|thursday|friday|saturday|sunday))|latest|most\s+recent|current)\b"
)
# The entities of readings, each with the column that tells one series from another beside the patient: a lab's
# test. The medication list and the diagnoses are no readings: a drug started earlier is no stale answer.
ENTITIES = {"cardiology.labs": "test", "cardiology.vitals": None, "cardiology.ecgs": None, "psychiatry.sessions": None}


@verifier(order=1, description="Check the latest reading")
class LatestReadingVerifier:
    """A question for the last, latest, most recent or current reading is answered with that reading ("last" as a
    date, as in "last Thursday", is no such question). The cells
    cited from one tool result's labs (per test), vitals, ECGs or therapy sessions of a patient are a series, and
    a series none of whose cells is from its latest row fails the answer: the earlier readings may come beside
    the latest. Only cell figures are judged, from a result that labels a period and is no aggregate: a table
    lists readings and a computation combines them. Reads the grounding AIsolate wrote and the tool results of
    the exchange; no model, no state kept between exchanges."""

    def verify(self, answer: Answer, evidence: Sequence[Evidence], aisolate: AIsolateManager) -> Verdict:
        if not answer.grounding_json:
            return Verdict.pass_("no figure to check")
        if not LATEST.search(answer.question.lower()):
            return Verdict.pass_("the question does not ask for the latest")
        # A tool result is a JSON object, the evidence of passages a list.
        results = {e.id: json.loads(e.content_json) for e in evidence if e.content_json.startswith("{")}
        # Per series, in the order the answer first cites it: the latest period it cites, the latest period of the
        # series and what to call it.
        series: dict[tuple[Any, ...], tuple[str, str, str]] = {}
        for figure in json.loads(answer.grounding_json)["figures"]:
            reading = _reading(figure.get("source") or {}, results)
            if reading is not None:
                key, cited_period, latest, name = reading
                series[key] = (max(cited_period, series[key][0]) if key in series else cited_period, latest, name)
        for cited_period, latest, name in series.values():
            if latest > cited_period:
                return Verdict.fail(
                    f"the answer cites the {cited_period} {name}; the latest in the records is {latest}"
                )
        return Verdict.pass_("the latest reading is cited" if series else "no figure to check")


def _reading(
    source: dict[str, Any], results: dict[str, dict[str, Any]]
) -> tuple[tuple[Any, ...], str, str, str] | None:
    """The series a cited cell belongs to, the period of its row, the latest period of the series and what the
    series is of ("Troponin T", else the entity's name); None when there is nothing to judge: no tool result of
    the exchange, no reading entity, no period labelled, an aggregate, or no row or no period to compare."""
    result = results.get(source.get("evidence"))
    if result is None:
        return None
    labels, entity = result.get("labels") or {}, result.get("entity")
    period = labels.get("period")
    if entity not in ENTITIES or not period or labels.get("function"):
        return None
    rows, row_key = result.get("rows") or [], labels.get("row_key")
    row = next((r for r in rows if row_key in r and str(r[row_key]) == source.get("row_key")), None)
    if row is None or not isinstance(row.get(period), str):
        return None
    # A series is one patient's rows of one result and, where the entity has a concept, one concept's.
    concept = ENTITIES[entity]
    same = [column for column in (labels.get("subject"), concept) if column]
    latest = max(
        r[period] for r in rows if isinstance(r.get(period), str) and all(r.get(c) == row.get(c) for c in same)
    )
    concept_value = row.get(concept) if concept else None
    name = concept_value if isinstance(concept_value, str) and concept_value else entity.rsplit(".", 1)[-1]
    return (source["evidence"], *(row.get(c) for c in same)), row[period], latest, name
