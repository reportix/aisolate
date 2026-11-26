import json
import re
from collections.abc import Iterator, Sequence
from typing import Any

from aisolate import AIsolateManager, Answer, DatabaseError, Evidence, Verdict, verifier

from acme_hr.pre_llm.contracts import CONTEXT_PREPARATOR, unavailable_cause
from acme_hr.tools.contract_tools import TERMS, SALARY

# The terms a contract states in digits, each as its sections word it: the number is the first after
# the term's word in the same sentence. A term in words ("three months") is not compared.
STATED = {
    "salary_eur": re.compile(r"salary[^.]*? ([\d,]+) EUR"),
    "bonus_eur": re.compile(r"bonus of ([\d,]+) EUR"),
    "weekly_hours": re.compile(r"(\d+) hours per week"),
    "vacation_days": re.compile(r"(\d+) vacation days"),
}
TITLE = "Employment Contract "
# The sections as filed: an amendment comes after the section it changes.
SECTIONS = "SELECT section, body FROM hr.documents WHERE context_preparator = %s AND title = %s ORDER BY id"
# Everyone with a contract: the names a question may drop that the asking user may not read.
EMPLOYEES = "SELECT employee FROM hr.contract_terms"
# The one answer allowed when the question is about someone the evidence does not hold.
DONT_KNOW = "i don't know"

Section = tuple[str, str]


@verifier(order=1, description="Check terms against contract", data_sources=["hr"])
class ContractTermsVerifier:
    """Every figure the answer cites from the HR system's terms (hr.contract_terms: salary, bonus, weekly hours,
    vacation days) is what that employee's signed contract states: its sections and its amendments, the last
    statement of a term being the current one. The contract is read from the data source hr as filed, never from
    the passages of the exchange: retrieval ranks them by likeness and may leave an amendment out. A figure no
    section states is a WARN, not a wrong answer: an amendment not filed, or a number mistyped at onboarding.
    Two FAILs, for an answer about someone else: a question or an answer that names an employee of whom no row
    and no passage was read must be I don't know, whatever the question claims about who is asking (the asking
    user's own row answers for nobody else, not as a yes or no either); and a figure AIsolate marked a mismatch,
    the answer claiming it is about someone or something the row is not.
    Reads the grounding AIsolate wrote; no model, no state kept between exchanges."""

    def verify(self, answer: Answer, evidence: Sequence[Evidence], aisolate: AIsolateManager) -> Verdict:
        strangers = _strangers(answer, evidence, aisolate)
        if strangers and not answer.text.strip().lower().startswith(DONT_KNOW):
            return Verdict.fail(f"nothing of {' and '.join(strangers)} was read, so the answer had to be I don't know")
        if not answer.grounding_json:
            return Verdict.pass_("no figure to check")
        grounding = json.loads(answer.grounding_json)
        mismatched = [f for f in grounding["figures"] if f.get("status") == "mismatch"]
        if mismatched:
            return Verdict.fail("; ".join(f"{f['display']} is not what the answer claims: {f.get('reason') or 'its row says otherwise'}" for f in mismatched))
        # A tool result is a JSON object, the evidence of passages a list.
        results = {e.id: json.loads(e.content_json) for e in evidence if e.content_json.startswith("{")}
        contracts: dict[str, list[Section] | None] = {}
        checked: list[str] = []
        problems: list[str] = []
        for display, source in _sources(grounding):
            term = _term(source, results)
            if term is None:
                continue
            employee, column, value = term
            if employee not in contracts:
                contracts[employee] = _read_contract(employee, aisolate)
            sections = contracts[employee]
            if sections is None:
                problems.append(f"{employee}'s contract could not be read: {display} is unchecked")
                continue
            stated = _stated(column, sections)
            unit = ((results[source["evidence"]].get("labels") or {}).get("units") or {}).get(column, "")
            if stated is None:
                problems.append(f"{display} is not in {employee}'s contract: no section states it")
            elif stated[1] != value:
                problems.append(f"{display} is not in {employee}'s contract: {stated[0]} says {_show(stated[1], unit)}")
            else:
                checked.append(f"{display} is in {employee}'s contract: {stated[0]}")
        if problems:
            return Verdict.warn("; ".join(problems))
        if not checked:
            return Verdict.pass_("no figure to check")
        return Verdict.pass_(checked[0] if len(checked) == 1 else "every figure is in the contract")


def _strangers(answer: Answer, evidence: Sequence[Evidence], aisolate: AIsolateManager) -> list[str]:
    """The employees the question or the answer names, by full or first name, of whom no evidence was read: a row
    carries the employee, a passage the contract's title. Nobody when the data source hr is unavailable."""
    mentioned = f"{answer.question} {answer.text}"
    read = " ".join(e.content_json for e in evidence)
    # ponytail: a first name alone; two employees sharing one, or one named like a word ("May"), need the full name.
    return [name for name in _employees(aisolate) if _names(name, mentioned) and not _names(name, read)]


def _names(employee: str, text: str) -> bool:
    first = employee.split()[0]
    return re.search(rf"\b({re.escape(employee)}|{re.escape(first)})\b", text, re.IGNORECASE) is not None


def _employees(aisolate: AIsolateManager) -> list[str]:
    try:
        with aisolate.data_source("hr") as db:
            cursor = db.cursor()
            cursor.execute(EMPLOYEES)
            return [str(row[0]) for row in cursor.fetchall()]
    except (ValueError, DatabaseError) as unavailable:
        aisolate.log.warning("employees unavailable: %s", unavailable_cause(unavailable))
        return []


def _term(source: dict[str, Any], results: dict[str, dict[str, Any]]) -> tuple[str, str, float] | None:
    """The employee, column and value of a cell cited from the HR system's terms; None for anything else: a
    passage, another entity, an aggregate, a column not compared, or a row or value the result does not hold."""
    result = results.get(source.get("evidence"))
    column = source.get("column")
    # The salary comes from fetch_salary's entity (the same table, the salary alone) or from the terms.
    if result is None or result.get("entity") not in (TERMS.name, SALARY.name) or column not in STATED:
        return None
    labels = result.get("labels") or {}
    if labels.get("function"):
        return None
    row_key = labels.get("row_key")
    rows = result.get("rows") or ()
    row = next((r for r in rows if row_key in r and str(r[row_key]) == source.get("row_key")), None)
    if row is None:
        return None
    employee, value = row.get(TERMS.subject_name), _number(row.get(column))
    if not isinstance(employee, str) or value is None:
        return None
    return employee, column, value


def _stated(column: str, sections: Sequence[Section]) -> tuple[str, float] | None:
    """The section that states the term and its number, the last statement winning; None when none does."""
    # ponytail: the sections' order decides; parse "with effect from <date>" when amendments arrive out of order.
    stated = None
    for section, body in sections:
        for match in STATED[column].finditer(body):
            stated = (section, float(match.group(1).replace(",", "")))
    return stated


def _read_contract(employee: str, aisolate: AIsolateManager) -> list[Section] | None:
    """The employee's contract as filed in the data source hr; None when the airlock has no data source hr or
    the database does not answer, which the verdict says."""
    try:
        with aisolate.data_source("hr") as db:
            cursor = db.cursor()
            cursor.execute(SECTIONS, (CONTEXT_PREPARATOR, TITLE + employee))
            return [(str(section), str(body)) for section, body in cursor.fetchall()]
    except (ValueError, DatabaseError) as unavailable:
        aisolate.log.warning("contract of %s unavailable: %s", employee, unavailable_cause(unavailable))
        return None


def _number(raw: Any) -> float | None:
    """A cell's value as a number: an int, a float, or digits in a string; None for anything else."""
    if raw is None or isinstance(raw, bool):
        return None
    if isinstance(raw, int | float):
        return float(raw)
    if isinstance(raw, str):
        try:
            return float(raw.replace(",", ""))
        except ValueError:
            return None
    return None


def _show(number: float, unit: str) -> str:
    """A contract's number as the answer shows a figure: thousands grouped, the unit after it."""
    text = f"{int(number):,}" if number.is_integer() else f"{number:,}"
    return f"{text} {unit}" if unit else text


def _sources(grounding: dict[str, Any]) -> Iterator[tuple[str, dict[str, Any]]]:
    """Each source the answer cites, with the display it is shown under: a figure's, its computation's
    operands, ranked rows and row, a table cell's. Any status counts: grounding keeps the source of a
    flagged figure, and its flag says nothing of the contract. A null is left out of the grounding, so a
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
