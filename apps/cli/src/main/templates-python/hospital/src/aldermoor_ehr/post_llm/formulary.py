import json
import re
from collections.abc import Sequence

from aisolate import AIsolateManager, Answer, Evidence, Verdict, verifier

# The hospital's medication list, drug names only: no patient and no row ever reaches a verdict.
DRUGS = "SELECT DISTINCT drug FROM ehr.medications"


@verifier(order=2, description="Check the drugs named", data_sources=["ehr"])
class FormularyVerifier:
    """A drug the answer names in its own words is one the tools returned: the formulary, the drug names of
    the hospital's medication list, says which words are drugs, and a drug of it that the text names and no
    returned medication row holds fails the answer, whatever the model knows of it. A drug the question names
    is expected, not reported: the answer may say that patient does not take it. A figure's or a table's
    display is written in from a row and never scanned, nor are the words quoted verbatim from a passage. The
    formulary is read once, through the data source ``ehr``; a failure to read it propagates, and the airlock
    turns it into a warning."""

    def __init__(self) -> None:
        # The names as the list spells them, sorted: the first drug the answer names that no medication list
        # holds is the one the verdict names, whatever order the database answers in. Read on first use and
        # kept: a drug added to the list is unknown until the airlock restarts. No lock: two first reads store
        # equal tuples.
        self._formulary: tuple[str, ...] | None = None

    def verify(self, answer: Answer, evidence: Sequence[Evidence], aisolate: AIsolateManager) -> Verdict:
        if not answer.grounding_json:
            return Verdict.pass_("no figure to check")
        grounding = json.loads(answer.grounding_json)
        typed = " ".join(s["text"] for s in grounding["segments"] if s.get("text"))
        # Words found verbatim in a passage are the passage's, not the model's. The segment holds each between
        # double quotes: they go with it, and only so, or a short quote would cut words elsewhere. Longest first:
        # a quote inside another goes with the outer one.
        quoted = (q["text"] for q in grounding["quotes"] if q.get("status") == "quoted")
        for words in sorted(quoted, key=len, reverse=True):
            typed = typed.replace('"' + words + '"', " ")
        typed = typed.lower()
        question = answer.question.lower()
        returned = _returned(evidence)
        named = False
        for drug in self._drugs(aisolate):
            lower = drug.lower()
            word = re.compile(rf"\b{re.escape(lower)}\b")
            # A drug the question names is the user's own word: "Is he on X?" is answered "no, not X".
            if not word.search(typed) or word.search(question):
                continue
            named = True
            if lower not in returned:
                return Verdict.fail(f"names a drug the returned records do not hold: {drug}")
        return Verdict.pass_("every drug named is in the records" if named else "no drug named outside the records")

    def _drugs(self, aisolate: AIsolateManager) -> tuple[str, ...]:
        if self._formulary is None:
            with aisolate.data_source("ehr") as db:
                cursor = db.cursor()
                cursor.execute(DRUGS)
                # A blank name would be a word between any two words.
                self._formulary = tuple(sorted(drug for (drug,) in cursor.fetchall() if drug and drug.strip()))
        return self._formulary


def _returned(evidence: Sequence[Evidence]) -> set[str]:
    """The drugs of the medication rows the tools returned, lower-cased; passages are a JSON list, no result."""
    drugs: set[str] = set()
    for item in evidence:
        if item.content_json.startswith("{"):
            result = json.loads(item.content_json)
            if result.get("entity") == "ehr.medications":
                drugs.update(r["drug"].lower() for r in result.get("rows") or () if isinstance(r.get("drug"), str))
    return drugs
