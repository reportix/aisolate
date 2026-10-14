"""Turns the model's answer into the user's: each placeholder resolved against this exchange's evidence
and written in by AIsolate, each claim checked, every number and fact value the model typed flagged.
Deterministic, no model asked. A port of ``Grounding.java`` and ``GroundedAnswer.java``, method by method;
the golden set (``conformance/cases/grounding``) is the contract.
"""

from __future__ import annotations

import dataclasses
import decimal
import re
import sys
import unicodedata
from collections.abc import Collection
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

from aisolate import Passage

from aisolate_airlock import answer_format as af
from aisolate_airlock.answer_format import END, JAVA_WS, java_strip
from aisolate_airlock.evidence import Labels
from aisolate_airlock.prompts import I_DONT_KNOW

SOURCED = "sourced"
COMPUTED = "computed"
UNSOURCED = "unsourced"
MISMATCH = "mismatch"
NO_VALUE = "…"


def _category_class(*prefixes: str) -> str:
    """A regex character class of the Unicode general categories, as Java's ``\\p{L}`` etc."""
    ranges, start, previous = [], None, None
    for cp in range(sys.maxunicode + 1):
        if unicodedata.category(chr(cp))[0] in prefixes:
            if start is None:
                start = cp
            previous = cp
        elif start is not None:
            ranges.append((start, previous))
            start = None
    if start is not None:
        ranges.append((start, previous))
    return "".join(
        re.escape(chr(a)) if a == b else f"{re.escape(chr(a))}-{re.escape(chr(b))}" for a, b in ranges
    )


_L = _category_class("L")
_N = _category_class("N")
_LN = _category_class("L", "N")
_M = re.compile(f"[{_category_class('M')}]")
_S = "[ \\t\\n\\x0b\\f\\r]"  # Java's \s is ASCII

PLACEHOLDER = re.compile(r"\{([A-Za-z][A-Za-z0-9_-]*)}")
# A token holding a digit: 78,000.50, 2026-03-14, INV-2026-0412, Q1.
NUMBER = re.compile(
    f"(?<![{_LN}])(?:[{_LN}]+[.,:/-](?=[{_LN}]))*[{_LN}]*[{_N}](?:[{_LN}]|[.,:/-](?=[{_LN}]))*"
)
# A fact value's edges: never inside a word, nor inside a number token (the 5 mg of 2.5 mg).
FACT_BEFORE = f"(?<![{_LN}])(?<![{_N}][.,:/-])"
FACT_AFTER = f"(?![{_LN}])(?![.,:/-][{_N}])"
EVIDENCE_ID = re.compile(r"ev-[0-9]+", re.I | re.A)
YEAR = re.compile(r"(?<![0-9])(?:19|20)[0-9][0-9](?![0-9])")
ISO_LIKE = re.compile(r"[0-9 ./:-]+")
SIGN_BEFORE = re.compile(f"(?:^|[ \\t\\n\\x0b\\f\\r(\\[:])[-−+]{END}")
SIGN_WORD_BEFORE = re.compile(f"\\b(?:minus|plus|negative){_S}*{END}", re.I | re.A)
UNIT_BEFORE = re.compile(f"([€$£]|\\b[A-Z]{{3}}){_S}?{END}", re.A)
WORD_AFTER = re.compile(f"^{_S}?([%€$£]|[{_L}][{_LN}/]*)")
POSSESSIVE = re.compile(r"['’]s\b", re.A)
NOT_LN = re.compile(f"[^{_LN}]+")
JAVA_SPACES = re.compile(f"{_S}+")
BIG_DECIMAL = re.compile(r"[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

SCALES = frozenset(
    "k m mn bn mio mrd tsd thousand thousands million millions billion billions trillion trillions".split()
)
CURRENCIES = frozenset("EUR USD GBP CHF JPY CNY SEK NOK DKK PLN CZK HUF CAD AUD".split())
SYMBOLS = {"€": "EUR", "$": "USD", "£": "GBP", "euro": "EUR", "euros": "EUR", "percent": "%"}
SELF = frozenset({"self", "me", "you", "the user", "asking user"})

EXACT = decimal.Context(prec=34, rounding=decimal.ROUND_HALF_EVEN)  # DECIMAL128
# BigDecimal's add, subtract and multiply never round.
UNBOUNDED = decimal.Context(prec=decimal.MAX_PREC, Emax=decimal.MAX_EMAX, Emin=decimal.MIN_EMIN)


# ── the answer as the user gets it (GroundedAnswer.java) ──


def _json(value: Any) -> Any:
    if dataclasses.is_dataclass(value):
        skip = getattr(value, "_non_null", False)
        return {
            f.name: _json(getattr(value, f.name))
            for f in dataclasses.fields(value)
            if not (skip and getattr(value, f.name) is None)
        }
    if isinstance(value, (list, tuple)):
        return [_json(item) for item in value]
    return value


class _Json:
    _non_null = True

    def to_json(self) -> dict[str, Any]:
        return _json(self)


@dataclass(frozen=True)
class Segment(_Json):
    text: str | None = None
    figure: str | None = None
    table: str | None = None


@dataclass(frozen=True)
class Source(_Json):
    evidence: str | None
    entity: str | None
    column: str | None
    row_key: str | None
    subject: str | None
    period: str | None
    unit: str | None
    value: str | None
    display: str | None


@dataclass(frozen=True)
class By(_Json):
    evidence: str | None
    entity: str | None
    column: str | None


@dataclass(frozen=True)
class Call(_Json):
    _non_null = False
    tool: str | None
    args: dict[str, Any] | None


@dataclass(frozen=True)
class Computed(_Json):
    op: str | None
    of: list[Source] | None
    n: int | None
    by: By | None
    rows: list[Source] | None
    row: Source | None
    call: Call | None


@dataclass(frozen=True)
class Figure(_Json):
    id: str
    display: str
    value: str | None
    status: str
    source: Source | None
    computed: Computed | None
    reason: str | None


@dataclass(frozen=True)
class Cell(_Json):
    display: str
    value: str | None
    status: str
    source: Source | None


@dataclass(frozen=True)
class Rank(_Json):
    _non_null = False
    op: str | None
    by: str | None


@dataclass(frozen=True)
class Table(_Json):
    id: str
    status: str
    display: str
    evidence: str | None
    entity: str | None
    call: Call | None
    columns: list[str]
    rows: list[list[Cell]]
    rank: Rank | None
    reason: str | None


@dataclass(frozen=True)
class Quote(_Json):
    id: str | None
    status: str
    evidence: str | None
    index: int | None
    title: str | None
    section: str | None
    text: str | None
    reason: str | None


@dataclass(frozen=True)
class Provenance(_Json):
    _non_null = False
    status: str
    sourced: int
    computed: int
    unsourced: int
    mismatch: int

    @staticmethod
    def of(figures: list[Figure], tables: list[Table]) -> Provenance:
        counts = [0, 0, 0, 0]
        for status in [f.status for f in figures] + [t.status for t in tables]:
            counts[{SOURCED: 0, COMPUTED: 1, UNSOURCED: 2}.get(status, 3)] += 1
        status = (
            "has_mismatch"
            if counts[3] > 0
            else "has_unsourced"
            if counts[2] > 0
            else "all_sourced"
            if counts[0] + counts[1] > 0
            else "no_figures"
        )
        return Provenance(status, *counts)


@dataclass(frozen=True)
class GroundedAnswer(_Json):
    _non_null = False
    answer: str
    segments: list[Segment]
    figures: list[Figure]
    tables: list[Table]
    quotes: list[Quote]
    provenance: Provenance

    @staticmethod
    def plain(text: str) -> GroundedAnswer:
        """An answer with no reference in it: "I don't know", a greeting, prose without a number."""
        return GroundedAnswer(text, [Segment(text)], [], [], [], Provenance.of([], []))


# ── the evidence ──


@dataclass(frozen=True)
class Rows:
    """One tool result as an answer may cite it; ``by_handle`` its rows by handle, numbered across the exchange."""

    evidence: str
    call: Call
    entity: str | None
    columns: list[str]
    labels: Labels
    by_handle: dict[str, dict[str, Any]]


@dataclass(frozen=True)
class Passages:
    """The passages of one evidence; index 1 is the first."""

    evidence: str
    passages: list[Passage]


class _Mismatch(Exception):
    """A reference that does not hold; the message says why, in words a reader understands."""


@dataclass(frozen=True)
class _Resolved:
    rows: Rows
    handle: str
    row: dict[str, Any]
    column: str
    unit: str | None
    decimals: int | None
    display: str
    value: str | None
    number: Decimal | None
    source: Source

    def where(self) -> str:
        return f"{self.rows.evidence} {self.handle} {self.column}"

    def operand(self) -> Source:
        """The cell as one of several: its value travels with its source."""
        return dataclasses.replace(self.source, value=self.value, display=self.display)


class Grounding:
    def __init__(self, tool_results: Collection[Rows], documents: Collection[Passages], question: str | None):
        self.rows: dict[str, Rows] = {}
        self.passages: dict[str, Passages] = {}
        self.handles: dict[str, str] = {}
        self.units: set[str] = set()
        # Each value of a fact column, as _fact_key writes it, and the first cell holding it: "ev-3 r4 drug".
        self.facts: dict[str, str] = {}
        self.figure_units: dict[str, str | None] = {}
        for result in tool_results:
            self.rows[result.evidence] = result
            for handle in result.by_handle:
                self.handles[handle] = result.evidence
            self.units.update(result.labels.units.values())
            for column in result.labels.unit_columns.values():
                for row in result.by_handle.values():
                    if row.get(column) is not None:
                        self.units.add(_java_string(row.get(column)))
            for column in result.labels.facts:
                for handle, row in result.by_handle.items():
                    value = row.get(column)
                    if isinstance(value, str) and len(java_strip(value)) >= 3:
                        self.facts.setdefault(_fact_key(value), f"{result.evidence} {handle} {column}")
        for document in documents:
            self.passages[document.evidence] = document
        self.question_tokens = {_number_key(m.group()) for m in NUMBER.finditer(question or "")}
        self.question_key = _fact_key(question or "")
        self.fact_pattern = _fact_pattern(self.facts) if self.facts else None

    def ground(self, answer: af.ModelAnswer) -> GroundedAnswer:
        """The model's answer in the format, every placeholder resolved and every claim checked."""
        if answer.dont_know:
            return GroundedAnswer.plain(I_DONT_KNOW)
        refs: dict[str, af.Ref] = {}
        for ref in answer.figures:
            refs.setdefault(ref.id, ref)
        quotes: dict[str, Quote] = {}
        for quote_ref in answer.quotes:
            if quote_ref.id is not None and quote_ref.id not in refs:
                quotes.setdefault(quote_ref.id, self._quote(quote_ref))
        render = _Render(self, set(refs) | set(quotes))
        texts, ids, at = [], [], 0
        for placeholder in PLACEHOLDER.finditer(answer.text):
            texts.append(answer.text[at : placeholder.start()])
            ids.append(placeholder.group(1))
            at = placeholder.end()
        texts.append(answer.text[at:])
        figures: dict[str, Figure] = {}
        tables: dict[str, Table] = {}
        # First every reference and the words around it; the text left over is scanned after.
        for i, id in enumerate(ids):
            ref = refs.get(id)
            if ref is not None and ref.table is not None:
                if id not in tables:
                    tables[id] = self._table(id, ref.table)
            elif ref is not None or id not in quotes:
                if id not in figures:
                    figures[id] = self._figure(id, ref)
                figure = figures[id]
                around = [texts[i], texts[i + 1]]
                conflict = self._beside_figure(around, figure.status, self.figure_units.get(id))
                texts[i], texts[i + 1] = around
                if conflict is not None and figure.status != MISMATCH:
                    figures[id] = _mismatch(figure, conflict)
        for i, id in enumerate(ids):
            render.text(texts[i], True)
            if id in tables:
                render.table(tables[id])
            elif id in figures:
                render.figure(figures[id])
            else:
                quote = quotes[id]
                # Words not in the passage are the model's own: their numbers are unsourced too.
                render.text('"' + (quote.text or "") + '"', quote.status != "quoted")
        render.text(texts[-1], True)
        return render.done(list(quotes.values()))

    def raw(self, text: str | None) -> GroundedAnswer:
        """Text that is not in the format: every number and fact value in it is unsourced."""
        render = _Render(self, set())
        render.text(text or "", True)
        return render.done([])

    # ── figures ──

    def _figure(self, id: str, ref: af.Ref | None) -> Figure:
        try:
            if ref is None:
                raise _Mismatch(f"the answer places {{{id}}} but gives no reference for it")
            if (ref.cell is None) == (ref.computed is None):
                raise _Mismatch(
                    f"{id} names neither a cell nor a computation"
                    if ref.cell is None
                    else f"{id} names both a cell and a computation"
                )
            if ref.cell is not None:
                cell = self._resolve(ref.cell)
                conflict = _conflict(ref.about, [cell], cell.unit, [cell.column])
                sourced = Figure(id, cell.display, cell.value, SOURCED, cell.source, None, None)
                self.figure_units[id] = cell.unit
                return sourced if conflict is None else _mismatch(sourced, conflict)
            return self._computed(id, ref.computed, ref.about)
        except _Mismatch as mismatch:
            return Figure(id, NO_VALUE, None, MISMATCH, None, None, str(mismatch))

    def _computed(self, id: str, computed: af.Computed, about: af.About | None) -> Figure:
        op = computed.op or ""
        if op not in af.OPS:
            raise _Mismatch("unknown operation " + op)
        if op in ("top_n", "bottom_n"):
            return self._ranking(id, computed)
        if op in ("max", "min") and computed.by is not None:
            return self._extreme(id, computed, about)
        cells = computed.of or []
        arity = len(cells)
        binary = op in ("subtract", "divide", "percent_change")
        several = op in ("add", "multiply")
        if (binary and arity != 2) or (several and arity < 2) or arity < 1:
            need = "two cells" if binary else "two cells or more" if several else "a cell"
            raise _Mismatch(f"{op} needs {need}, got {arity}")
        operands = [self._resolve(cell) for cell in cells]
        of = [operand.operand() for operand in operands]
        if op == "count":
            return self._checked(
                id,
                str(len(operands)),
                str(len(operands)),
                None,
                Computed(op, of, None, None, None, None, None),
                about,
                operands,
            )
        numbers = []
        for operand in operands:
            if operand.number is None:
                raise _Mismatch(operand.where() + " is not a number")
            numbers.append(operand.number)
        operand_units = list(dict.fromkeys(o.unit for o in operands if o.unit is not None))
        same_unit = len(operand_units) <= 1
        unit = operand_units[0] if operand_units else None
        picked = None
        if op in ("add", "sum"):
            result = Decimal(0)
            for n in numbers:
                result = UNBOUNDED.add(result, n)
        elif op == "subtract":
            result = UNBOUNDED.subtract(numbers[0], numbers[1])
        elif op == "multiply":
            result = Decimal(1)
            for n in numbers:
                result = UNBOUNDED.multiply(result, n)
            unit = unit if len(operand_units) == 1 else None
            same_unit = True
        elif op == "divide":
            result = _divide(numbers[0], numbers[1])
            # EUR over days is a rate in no unit AIsolate names; EUR over a count stays EUR.
            unit = operands[0].unit if operands[1].unit is None else None
            same_unit = True
        elif op == "percent_change":
            result = UNBOUNDED.multiply(
                _divide(UNBOUNDED.subtract(numbers[1], numbers[0]), numbers[0]), Decimal(100)
            )
            unit = "%"
        elif op == "average":
            total = Decimal(0)
            for n in numbers:
                total = UNBOUNDED.add(total, n)
            result = _divide(total, Decimal(len(numbers)))
        else:
            # max or min over cells: the value, and which of them holds it
            best = 0
            for i in range(1, len(numbers)):
                if (numbers[i] > numbers[best]) if op == "max" else (numbers[i] < numbers[best]):
                    best = i
            result = numbers[best]
            picked = operands[best]
        result = _java(result)
        if not same_unit:
            raise _Mismatch(f"{op} mixes " + " and ".join(operand_units))
        rounded = op in ("divide", "average", "percent_change")
        exact = _set_scale(result, 10, decimal.ROUND_HALF_EVEN) if rounded else result
        # The operands' declared decimals, where they agree; a percentage is no longer in their unit.
        declared = list(dict.fromkeys(o.decimals for o in operands))
        decimals = declared[0] if len(declared) == 1 and op != "percent_change" else None
        display = _with_unit(_group(_shown(result, decimals, rounded), True), unit)
        provenance = Computed(op, of, None, None, None, None if picked is None else picked.source, None)
        # A max or a min is about the row that holds it, not every row it was taken over.
        return self._checked(
            id, display, plain(exact), unit, provenance, about, operands if picked is None else [picked]
        )

    def _checked(
        self,
        id: str,
        display: str,
        value: str,
        unit: str | None,
        computed: Computed,
        about: af.About | None,
        about_rows: list[_Resolved],
    ) -> Figure:
        figure = Figure(id, display, value, COMPUTED, None, computed, None)
        self.figure_units[id] = unit
        conflict = _conflict(about, about_rows, unit, [r.column for r in about_rows])
        return figure if conflict is None else _mismatch(figure, conflict)

    def _ranking(self, id: str, computed: af.Computed) -> Figure:
        """``top_n`` and ``bottom_n``: the cited rows are exactly the best n by the column."""
        by = computed.by
        if by is None or not computed.rows:
            raise _Mismatch(f"{computed.op} needs by (evidence and column) and the ranked rows")
        result = self._rows_of(by.evidence)
        _rankable(result, by.column)
        cited = computed.rows
        ranked = [self._resolve(af.Cell(by.evidence, handle, by.column)).operand() for handle in cited]
        reason = (
            f"n is {computed.n} but {len(cited)} rows are ranked"
            if computed.n is not None and computed.n != len(cited)
            else self._rank_conflict(result, by.column, cited, computed.op == "top_n")
        )
        names = ", ".join(_source_name(s) for s in ranked)
        figure = Figure(
            id,
            names,
            None,
            COMPUTED,
            None,
            Computed(
                computed.op, None, len(cited), By(by.evidence, result.entity, by.column), ranked, None, result.call
            ),
            None,
        )
        return figure if reason is None else _mismatch(figure, reason)

    def _extreme(self, id: str, computed: af.Computed, about: af.About | None) -> Figure:
        """``max`` or ``min`` over one evidence's rows: the value, and the row holding it."""
        by = computed.by
        result = self._rows_of(by.evidence)
        _rankable(result, by.column)
        is_max = computed.op == "max"
        winners = _best(result, by.column, is_max)
        if not winners:
            raise _Mismatch(f"{by.evidence} has no {by.column} to take the {computed.op} of")
        cited = computed.rows or []
        reason = None
        for handle in cited:
            self._row_of(result, handle)
            if handle not in winners:
                reason = (
                    f"{_row_name(result, handle)} does not hold the {'highest ' if is_max else 'lowest '}"
                    f"{by.column} of {by.evidence}; {_row_name(result, winners[0])} does"
                )
                break
        row = self._resolve(af.Cell(by.evidence, cited[0] if cited else winners[0], by.column))
        figure = Figure(
            id,
            row.display,
            row.value,
            COMPUTED,
            None,
            Computed(
                computed.op, None, None, By(by.evidence, result.entity, by.column), None, row.source, result.call
            ),
            None,
        )
        self.figure_units[id] = row.unit
        conflict = reason if reason is not None else _conflict(about, [row], row.unit, [by.column])
        return figure if conflict is None else _mismatch(figure, conflict)

    # ── tables ──

    def _table(self, id: str, ref: af.Table) -> Table:
        result = self.rows.get(ref.evidence)
        columns = ref.columns or []
        cited = ref.rows or []
        reason = None
        try:
            self._rows_of(ref.evidence)
            if not columns or not cited:
                raise _Mismatch("a table needs rows and columns")
            if len(set(cited)) < len(cited):
                raise _Mismatch("a row is listed twice")
            if ref.rank is not None:
                if ref.rank.op not in ("top_n", "bottom_n"):
                    raise _Mismatch("unknown ranking " + _s(ref.rank.op))
                _rankable(result, ref.rank.by)
                for handle in cited:
                    self._row_of(result, handle)
                reason = self._rank_conflict(result, ref.rank.by, cited, ref.rank.op == "top_n")
        except _Mismatch as mismatch:
            reason = str(mismatch)
        cells: list[list[Cell]] = []
        markdown = "| " + " | ".join(_cell_text(c) for c in columns) + " |\n|" + " --- |" * len(columns)
        for handle in cited:
            line = []
            for column in columns:
                try:
                    cell = self._resolve(af.Cell(ref.evidence, handle, column))
                    line.append(Cell(cell.display, cell.value, SOURCED, cell.source))
                except _Mismatch as mismatch:
                    reason = reason if reason is not None else str(mismatch)
                    line.append(Cell(NO_VALUE, None, MISMATCH, None))
            cells.append(line)
            markdown += "\n| " + " | ".join(_cell_text(c.display) for c in line) + " |"
        return Table(
            id,
            SOURCED if reason is None else MISMATCH,
            markdown,
            ref.evidence,
            None if result is None else result.entity,
            None if result is None else result.call,
            columns,
            cells,
            None if ref.rank is None else Rank(ref.rank.op, ref.rank.by),
            reason,
        )

    # ── rankings ──

    def _rank_conflict(self, result: Rows, column: str, cited: list[str], top: bool) -> str | None:
        """Why ``cited`` is not the best ``len(cited)`` rows by ``column``, in order; None when it is.
        Ties at the cut may be either row."""
        if len(set(cited)) < len(cited):
            return "a row is ranked twice"
        values = _rank_values(result, column)
        for handle in cited:
            if handle not in values:
                return f"{_row_name(result, handle)} has no {column} to rank by"
        # Below zero: the first ranks before the second.
        direction = -1 if top else 1
        ordered = sorted(values.values(), reverse=top)
        if len(cited) > len(ordered):
            return f"{result.evidence} holds only {len(ordered)} rows with {column}"
        cut = ordered[len(cited) - 1]
        rank = f"{'top ' if top else 'bottom '}{len(cited)} by {column}"
        for i in range(1, len(cited)):
            if direction * _cmp(values[cited[i - 1]], values[cited[i]]) > 0:
                return (
                    f"the rows are out of order: {_row_name(result, cited[i])} ranks before "
                    f"{_row_name(result, cited[i - 1])}"
                )
        for handle, value in values.items():
            if handle not in cited and direction * _cmp(value, cut) < 0:
                return f"{_row_name(result, handle)} belongs in the {rank} and is missing"
        for handle in cited:
            if direction * _cmp(values[handle], cut) > 0:
                return f"{_row_name(result, handle)} is not in the {rank}"
        return None

    # ── quotes ──

    def _quote(self, ref: af.QuoteRef) -> Quote:
        document = self.passages.get(ref.evidence)
        reason = None
        passage = None
        if document is None:
            reason = (
                f"{ref.evidence} is a tool result, not a document"
                if ref.evidence in self.rows
                else f"there is no document {_s(ref.evidence)} in this exchange"
            )
        elif ref.index is None or ref.index < 1 or ref.index > len(document.passages):
            reason = f"{ref.evidence} has no passage {_s(ref.index)}"
        else:
            passage = document.passages[ref.index - 1]
            if ref.text is None or not java_strip(ref.text):
                reason = "the quote is empty"
            elif _spaces(ref.text) not in _spaces(passage.body):
                reason = f"these words are not in {ref.evidence}, passage {ref.index}"
        return Quote(
            ref.id,
            "quoted" if reason is None else MISMATCH,
            ref.evidence,
            ref.index,
            None if passage is None else passage.title,
            None if passage is None else passage.section,
            ref.text,
            reason,
        )

    # ── cells ──

    def _resolve(self, cell: af.Cell | None) -> _Resolved:
        if cell is None or cell.evidence is None or cell.row is None or cell.column is None:
            raise _Mismatch("a cell needs an evidence id, a row and a column")
        result = self._rows_of(cell.evidence)
        row = self._row_of(result, cell.row)
        column = cell.column
        # Masked values arrive as stars; a hidden column does not arrive at all.
        if column in result.labels.masked or row.get(column) == "****":
            raise _Mismatch(f"column {column} of {cell.evidence} is masked: it cannot be cited")
        if column not in result.columns:
            raise _Mismatch(f"{cell.evidence} ({_s(result.entity)}) has no column {column}")
        labels = result.labels
        value = row.get(column)
        unit = labels.units.get(column)
        if unit is None and column in labels.unit_columns:
            unit = _text(row.get(labels.unit_columns[column]))
        num = number(value)
        grouped = column not in (labels.row_key, labels.subject, labels.period)
        decimals = labels.decimals.get(column)
        # A group's average arrives with every decimal the database computed.
        average = labels.function == "avg" and column != labels.row_key
        display = (
            "—"
            if value is None
            else _with_unit(_group(_shown(num, decimals, average), grouped), unit)
            if num is not None
            else _java_string(value)
        )
        source = Source(
            cell.evidence,
            result.entity,
            column,
            _text(row.get(labels.row_key)),
            _text(row.get(labels.subject)),
            _text(row.get(labels.period)),
            unit if num is not None else None,
            None,
            None,
        )
        return _Resolved(
            result,
            cell.row,
            row,
            column,
            unit if num is not None else None,
            decimals,
            display,
            plain(num) if num is not None else _text(value),
            num,
            source,
        )

    def _rows_of(self, evidence: str | None) -> Rows:
        result = self.rows.get(evidence)
        if result is None:
            raise _Mismatch(
                f"{evidence} is a document, not a tool result: quote it"
                if evidence in self.passages
                else f"there is no evidence {_s(evidence)} in this exchange"
            )
        return result

    def _row_of(self, result: Rows, handle: str) -> dict[str, Any]:
        row = result.by_handle.get(handle)
        if row is None:
            owner = self.handles.get(handle)
            raise _Mismatch(
                f"{handle} is a row of {owner}, not of {result.evidence}"
                if owner is not None
                else f"{result.evidence} has no row {handle}"
            )
        return row

    # ── the words beside a placeholder ──

    def _beside_figure(self, around: list[str], status: str, unit: str | None) -> str | None:
        """No sign before a figure, no scale word and no other unit after it; a repeat of its own unit
        is dropped from ``around``, so it is not written twice."""
        if status == MISMATCH and unit is None:
            return None
        if SIGN_BEFORE.search(around[0]) or SIGN_WORD_BEFORE.search(around[0]):
            return "the text puts a sign before it"
        before = UNIT_BEFORE.search(around[0])
        if before and unit is not None and self._is_unit(before.group(1)):
            if not _same_unit(before.group(1), unit):
                return f"the text calls it {before.group(1)}, the row says {unit}"
            around[0] = around[0][: before.start()]
        after = WORD_AFTER.search(around[1])
        if after:
            word = after.group(1)
            if unit is not None and _same_unit(word, unit):
                around[1] = around[1][after.end() :]
            elif word.lower() in SCALES:
                return "the text scales it: " + word
            elif word == "%":
                return "the text makes it a percentage"
            elif unit is not None and self._is_unit(word):
                return f"the text calls it {word}, the row says {unit}"
        return None

    def _is_unit(self, word: str) -> bool:
        return word in CURRENCIES or word.lower() in SYMBOLS or any(_same_unit(u, word) for u in self.units)

    # ── typed numbers ──

    def _exempt(self, token: str) -> bool:
        """An evidence id, or a number the question holds, is not a figure."""
        return EVIDENCE_ID.fullmatch(token) is not None or _number_key(token) in self.question_tokens

    def _asked(self, key: str) -> bool:
        """The question names this fact value: the key is a whole phrase of it, with a fact's edges
        (insulinoma does not name insulin, 25 mg does not name 5 mg)."""
        # The substring test first spares compiling a pattern for every value the question lacks.
        return (
            key in self.question_key
            and re.search(FACT_BEFORE + re.escape(key) + FACT_AFTER, self.question_key) is not None
        )

    def _unsourced_reason(self, token: str) -> str:
        """What a typed number matches, so the reader sees where the model may have read it."""
        digits = token.replace(",", "")
        if not BIG_DECIMAL.fullmatch(digits):
            return "typed in the text, not referenced"
        typed = Decimal(digits)
        for result in self.rows.values():
            for handle, row in result.by_handle.items():
                for column in result.columns:
                    held = number(row.get(column))
                    if held is not None and held == typed and column not in result.labels.masked:
                        return (
                            f"typed in the text, not referenced; {result.evidence} {handle} {column}"
                            " holds the same value"
                        )
        return "typed in the text, not referenced"


class _Render:
    """The segments and figures of one answer, in the order the text places them."""

    def __init__(self, grounding: Grounding, taken: set[str]):
        self.grounding = grounding
        self.segments: list[Segment] = []
        self.figures: dict[str, Figure] = {}
        self.tables: dict[str, Table] = {}
        self.taken = taken
        self.answer = ""
        self.unsourced = 0

    def text(self, text: str, scan: bool) -> None:
        """Text as written; with ``scan``, every fact value and every number in it not in the question is
        unsourced. Facts first: "5 mg" of a dose is one value, not the number 5."""
        grounding = self.grounding
        at = 0
        for fact in grounding.fact_pattern.finditer(text) if scan and grounding.fact_pattern is not None else ():
            key = _fact_key(fact.group())
            where = grounding.facts.get(key)
            # None: a letter the pattern's case folding matches and lower-casing does not (ſ for s).
            if where is None or grounding._asked(key):
                continue
            self._numbers(text[at : fact.start()], scan)
            self._unsourced(fact.group(), f"typed in the text, not referenced; {where} holds the same value")
            at = fact.end()
        self._numbers(text[at:], scan)

    def _numbers(self, text: str, scan: bool) -> None:
        at = 0
        for number_ in NUMBER.finditer(text) if scan else ():
            start, end, token = number_.start(), number_.end(), number_.group()
            if self.grounding._exempt(token) or _list_marker(self.answer + text[at:start], text, end, token):
                continue
            if start > 0 and text[start - 1] in "-−" and (start == 1 or not _letter_or_digit(text[start - 2])):
                start -= 1
            self._plain(text[at:start])
            self._unsourced(text[start:end], self.grounding._unsourced_reason(token))
            at = end
        self._plain(text[at:])

    def _unsourced(self, display: str, reason: str) -> None:
        while True:
            self.unsourced += 1
            id = f"u{self.unsourced}"
            if id not in self.taken:
                break
        self.figure(Figure(id, display, None, UNSOURCED, None, None, reason))

    def figure(self, figure: Figure) -> None:
        self.figures.setdefault(figure.id, figure)
        self.segments.append(Segment(figure=figure.id))
        self.answer += figure.display

    def table(self, table: Table) -> None:
        self.tables.setdefault(table.id, table)
        self.segments.append(Segment(table=table.id))
        if self.answer and not self.answer.endswith("\n"):
            self.answer += "\n"
        self.answer += table.display + "\n"

    def _plain(self, text: str) -> None:
        if not text:
            return
        last = self.segments[-1] if self.segments else None
        if last is not None and last.text is not None:
            self.segments[-1] = Segment(last.text + text)
        else:
            self.segments.append(Segment(text))
        self.answer += text

    def done(self, quotes: list[Quote]) -> GroundedAnswer:
        figures = list(self.figures.values())
        tables = list(self.tables.values())
        return GroundedAnswer(self.answer, list(self.segments), figures, tables, quotes, Provenance.of(figures, tables))


# ── claims ──


def _conflict(
    about: af.About | None, rows: list[_Resolved], unit: str | None, columns: list[str]
) -> str | None:
    """Where the model's claim of what a figure is about disagrees with its rows; None when it does
    not. A label the rows do not carry cannot disagree."""
    claimed = None if about is None else about.subject
    is_self = claimed is not None and java_strip(claimed).lower() in SELF
    for row in rows:
        own = row.row.get("self") is True
        if is_self and not own:
            return "the row is not the asking user's own"
        # Beside the user's own row, a figure from another row must say whom it is about.
        if claimed is None and not own and any(r.get("self") is True for r in row.rows.by_handle.values()):
            return "the row is not the asking user's own, and the figure does not say whom it is about"
        subject = row.source.subject
        function = row.rows.labels.function
        # A group's value is about no one: an average is not one patient's reading.
        if claimed is not None and not is_self and subject is None and function is not None:
            key = "" if row.source.row_key is None else f" ({row.source.row_key})"
            return f"the row is the {function} of a group{key}, not about {java_strip(claimed)}"
        if claimed is not None and not is_self and subject is not None and not _same_name(claimed, subject):
            return f"the row is about {subject}, not {java_strip(claimed)}"
    if about is None:
        return None
    if about.period is not None:
        periods = [r.source.period for r in rows if r.source.period is not None]
        if periods and not any(_same_period(about.period, p) for p in periods):
            return f"the row is about {', '.join(periods)}, not {java_strip(about.period)}"
    if about.concept is not None and not any(_same_concept(about.concept, r) for r in rows):
        return f"the figure is {', '.join(columns)}, not {java_strip(about.concept)}"
    if about.unit is not None and unit is not None and not _same_unit(about.unit, unit):
        return f"the figure is in {unit}, not {java_strip(about.unit)}"
    return None


def _same_concept(claimed: str, row: _Resolved) -> bool:
    """The column's name, or, where each row holds one figure of a named concept, a text of the row
    holding every word of the claim."""
    if _same_words(claimed, row.column):
        return True
    claim = _words(claimed)
    return bool(claim) and any(isinstance(v, str) and _words(v) >= claim for v in row.row.values())


def _rankable(result: Rows, column: str | None) -> None:
    if column is None or column not in result.columns:
        raise _Mismatch(f"{result.evidence} ({_s(result.entity)}) has no column {_s(column)}")
    if column in result.labels.masked:
        raise _Mismatch(f"column {column} of {result.evidence} is masked: it cannot be cited")


def _best(result: Rows, column: str, is_max: bool) -> list[str]:
    values = _rank_values(result, column)
    if not values:
        return []
    items = list(values.values())
    best = items[0]
    for value in items[1:]:
        best = best if (_cmp(best, value) >= 0) == is_max else value
    return [handle for handle, value in values.items() if _cmp(value, best) == 0]


def _rank_values(result: Rows, column: str) -> dict[str, Any]:
    """Numbers by value, text (ISO dates) by its order. A column holding both cannot be ranked, nor one
    whose rows are in different units: 28,000 GBP is not below 78,000 EUR."""
    values: dict[str, Any] = {}
    kinds: set[type] = set()
    row_units: set[str] = set()
    unit_column = result.labels.unit_columns.get(column)
    for handle, row in result.by_handle.items():
        value = row.get(column)
        comparable = number(value) if _is_number(value) else value if isinstance(value, str) else None
        if comparable is not None:
            kinds.add(type(comparable))
            values[handle] = comparable
            if unit_column is not None and row.get(unit_column) is not None:
                row_units.add(_java_string(row.get(unit_column)))
    if len(kinds) > 1:
        raise _Mismatch(f"column {column} of {result.evidence} holds numbers and text: it cannot be ranked")
    if len(row_units) > 1:
        raise _Mismatch(
            f"{column} of {result.evidence} is in {' and '.join(sorted(row_units))}: its rows cannot be ranked"
        )
    return values


def _mismatch(figure: Figure, reason: str) -> Figure:
    return Figure(figure.id, figure.display, None, MISMATCH, figure.source, figure.computed, reason)


def _list_marker(before: str, text: str, end: int, token: str) -> bool:
    """A list item's number: alone at the start of its line, then ``.`` or ``)``."""
    line = before[before.rfind("\n") + 1 :]
    return (
        not java_strip(line)
        and all(unicodedata.category(c) == "Nd" for c in token)
        and end + 1 < len(text)
        and text[end] in ".)"
        and text[end + 1] in JAVA_WS
    )


def _letter_or_digit(c: str) -> bool:
    category = unicodedata.category(c)
    return category[0] == "L" or category == "Nd"


# ── values ──


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, Decimal, float)) and not isinstance(value, bool)


def number(value: Any) -> Decimal | None:
    """A JSON number as an exact decimal; None for anything else (a bool is no number, as in Java)."""
    if not _is_number(value):
        return None
    if isinstance(value, float):
        return None if value != value or value in (float("inf"), float("-inf")) else _java(Decimal(repr(value)))
    if isinstance(value, Decimal):
        return _java(value) if value.is_finite() else None
    return Decimal(value)


def _java(value: Decimal) -> Decimal:
    # BigDecimal has no negative zero.
    return value.copy_abs() if value.is_zero() else value


def _scale(value: Decimal) -> int:
    return -value.as_tuple().exponent


def _set_scale(value: Decimal, scale: int, rounding: str) -> Decimal:
    return _java(value.quantize(Decimal(1).scaleb(-scale), rounding=rounding, context=UNBOUNDED))


def _strip(value: Decimal) -> Decimal:
    return _java(value.normalize(UNBOUNDED))


def _divide(dividend: Decimal, divisor: Decimal) -> Decimal:
    if divisor.is_zero():
        raise _Mismatch("it divides by zero")
    return _java(EXACT.divide(dividend, divisor))


def plain(value: Decimal) -> str:
    """The value as digits, without trailing zeros: 78000.00 is 78000."""
    stripped = _strip(value)
    if _scale(stripped) < 0:
        stripped = _set_scale(stripped, 0, decimal.ROUND_HALF_UP)
    return format(stripped, "f")


def _shown(number_: Decimal, decimals: int | None, average: bool) -> Decimal:
    """What a figure shows: the declared decimals, all shown; else an average to two, its trailing zeros
    dropped (119.25, 15.1); else as held."""
    if decimals is not None:
        return _set_scale(number_, decimals, decimal.ROUND_HALF_UP)
    return _strip(_set_scale(number_, 2, decimal.ROUND_HALF_UP)) if average else number_


def _group(value: Decimal, grouped: bool) -> str:
    """78000.00 as 78,000; 1234.50 as 1,234.50: zero cents go, cents stay."""
    scale = 0 if _scale(_strip(value)) <= 0 else _scale(value)
    return format(_set_scale(value, scale, decimal.ROUND_HALF_UP), ",f" if grouped else "f")


def _with_unit(number_: str, unit: str | None) -> str:
    return number_ if unit is None else number_ + "%" if unit == "%" else f"{number_} {unit}"


def _text(value: Any) -> str | None:
    if value is None:
        return None
    return plain(number(value)) if _is_number(value) else _java_string(value)


def _java_string(value: Any) -> str:
    """``String.valueOf``: true, not True."""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _s(value: Any) -> str:
    """Java string concatenation: null, not None."""
    return "null" if value is None else _java_string(value)


def _cmp(a: Any, b: Any) -> int:
    return (a > b) - (a < b)


def _source_name(source: Source) -> str | None:
    return source.subject if source.subject is not None else source.row_key if source.row_key is not None else source.display


def _row_name(result: Rows, handle: str) -> str:
    row = result.by_handle.get(handle)
    subject = None if row is None else _text(row.get(result.labels.subject))
    return handle if subject is None else f"{subject} ({handle})"


def _cell_text(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


# ── comparing claims ──


def _same_name(claimed: str, label: str) -> bool:
    claim = _words(POSSESSIVE.sub("", claimed))
    held = _words(label)
    return bool(claim) and (held >= claim or claim >= held)


def _same_words(claimed: str, column: str) -> bool:
    claim = _words(claimed)
    held = _words(column)
    return bool(claim) and (held >= claim or claim >= held)


def _same_period(claimed: str, label: str) -> bool:
    """2026 is 2026-03-14 and FY2026; 2026-04 is not 2026-03-14. A period in words compares by its years."""
    claim = java_strip(claimed).lower()
    held = java_strip(label).lower()
    if not claim or held.startswith(claim) or claim.startswith(held):
        return True
    if ISO_LIKE.fullmatch(claim) and ISO_LIKE.fullmatch(held):
        return False
    claim_years = set(YEAR.findall(claim))
    held_years = set(YEAR.findall(held))
    return not claim_years or not held_years or bool(claim_years & held_years)


def _same_unit(a: str, b: str) -> bool:
    return _canonical_unit(a) == _canonical_unit(b)


def _canonical_unit(unit: str) -> str:
    stripped = java_strip(unit)
    mapped = SYMBOLS.get(stripped.lower(), SYMBOLS.get(stripped, stripped)).lower()
    return mapped[:-1] if len(mapped) > 3 and mapped.endswith("s") else mapped


def _words(text: str) -> set[str]:
    plain_ = _M.sub("", unicodedata.normalize("NFD", text)).lower()
    return {word for word in NOT_LN.split(plain_) if word}


def _spaces(text: str) -> str:
    return JAVA_SPACES.sub(" ", java_strip(text))


def _number_key(token: str) -> str:
    """A number as the question and the answer may both write it: 70,000 is 70000."""
    return token.lower().replace(",", "")


def _fact_key(text: str) -> str:
    """A fact value as a row, the question and the answer may all write it: Twice  Daily is twice daily."""
    return _spaces(text).lower()


def _fact_pattern(keys: Collection[str]) -> re.Pattern[str]:
    """Any of ``keys`` as typed: in any case, any spaces between its words, never inside a longer word or a
    number; the longest first, so "insulin glargine" is not taken for "insulin"."""
    alternatives = "|".join(
        f"{_S}+".join(re.escape(word) for word in key.split(" ")) for key in sorted(keys, key=len, reverse=True)
    )
    return re.compile(f"{FACT_BEFORE}(?:{alternatives}){FACT_AFTER}", re.I)
