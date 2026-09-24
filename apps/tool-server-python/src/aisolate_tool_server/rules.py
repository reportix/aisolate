"""Rule sets and their evaluation (Java: libs/config ``RuleSet``, ``Rule``, ``RowFilter``; the tool
server's ``RulesEvaluator``, ``Obligations``)."""

from __future__ import annotations

import enum
import json
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from aisolate_runtime.identity import Principal

_WS = "[ \t\n\x0b\f\r]"  # Java's \s; Python's would match Unicode spaces too
_EQUALITY = re.compile(
    rf"{_WS}*([a-z_][a-z0-9_]*){_WS}*=={_WS}*(?:principal\.([a-z_][a-z0-9_]*)|'([a-z0-9_-]+)'){_WS}*"
)
_KEYS = {"entity", "classification", "rules"}
_RULE_KEYS = {"name", "principals", "actions", "effect", "row_filter", "columns", "aggregation"}


class Action(enum.StrEnum):
    READ = "read"
    AGGREGATE = "aggregate"


class Effect(enum.StrEnum):
    ALLOW = "allow"
    DENY = "deny"


class ColumnAction(enum.StrEnum):
    HIDE = "hide"
    MASK = "mask"


def java_trim(text: str) -> str:
    """``String.trim()``: every character up to U+0020 at either end."""
    return text.strip("".join(map(chr, range(33))))


@dataclass(frozen=True, slots=True)
class RowFilter:
    column: str | None
    attribute: str | None
    literal: str | None

    @property
    def matches_all(self) -> bool:
        return self.column is None

    @staticmethod
    def parse(text: str | None) -> RowFilter:
        if text is None:
            raise ValueError("row_filter is required on an allow rule")
        if java_trim(text) == "true":
            return RowFilter(None, None, None)
        m = _EQUALITY.fullmatch(text)
        if m is None:
            raise ValueError(
                "row_filter must be 'true', '<column> == principal.<attribute>' or \"<column> =="
                f" '<literal>'\": {text}"
            )
        return RowFilter(m.group(1), m.group(2), m.group(3))


@dataclass(frozen=True, slots=True)
class Rule:
    name: str | None
    role: str
    actions: tuple[Action, ...]
    effect: Effect
    row_filter: str | None
    columns: Mapping[str, ColumnAction]
    min_group_size: int | None

    def applies_to(self, role: str, action: Action) -> bool:
        return role == self.role and action in self.actions


@dataclass(frozen=True, slots=True)
class RuleSet:
    entity: str
    classification: str | None
    rules: tuple[Rule, ...]

    @staticmethod
    def parse(text: str) -> RuleSet:
        """One rule set file; ValueError with Java's message (``Invalid rules JSON: ...`` for what
        Jackson refuses while reading)."""
        try:
            node = json.loads(text, object_pairs_hook=_no_duplicates)
        except ValueError as e:
            raise ValueError(f"Invalid rules JSON: {e}") from e
        if not isinstance(node, dict):
            raise ValueError("a rule set is a JSON object")
        for key in node:
            if key not in _KEYS:
                raise ValueError(f"unknown key '{key}' (a rule set has entity, classification and rules)")
        entity = _string(node.get("entity"), "entity")
        classification = _string(node.get("classification"), "classification")
        raw_rules = node.get("rules")
        if raw_rules is not None and not isinstance(raw_rules, list):
            raise ValueError("Invalid rules JSON: rules must be an array")
        rules = tuple(_rule(raw) for raw in raw_rules or [])
        if entity is None or not entity.strip():
            raise ValueError("a rule set needs an entity")
        return RuleSet(entity, classification, rules)


@dataclass(frozen=True, slots=True)
class RowPredicate:
    column: str
    value: Any


@dataclass(frozen=True, slots=True)
class Obligations:
    all_rows: bool
    rows: tuple[RowPredicate, ...]
    hidden: frozenset[str]
    masked: frozenset[str]
    min_group_size: int | None
    matched_rules: tuple[str, ...]


def evaluate(principal: Principal, rule_set: RuleSet, action: Action) -> Obligations | None:
    """None is deny: no rule matched, a matched deny, or a row filter needing an attribute the user lacks."""
    matched = [r for r in rule_set.rules if any(r.applies_to(role, action) for role in principal.roles)]
    if not matched or any(r.effect == Effect.DENY for r in matched):
        return None
    all_rows = False
    rows: list[RowPredicate] = []
    hidden: set[str] = set()
    masked: set[str] = set()
    min_group_size: int | None = None
    for rule in matched:
        row_filter = RowFilter.parse(rule.row_filter)
        if row_filter.matches_all:
            all_rows = True
        else:
            value = row_filter.literal if row_filter.literal is not None else principal.attributes.get(row_filter.attribute)
            if value is None:
                return None  # fail closed: one unusable filter denies the whole query
            rows.append(RowPredicate(row_filter.column, value))
        for column, act in rule.columns.items():
            (hidden if act == ColumnAction.HIDE else masked).add(column)
        if rule.min_group_size is not None:
            min_group_size = rule.min_group_size if min_group_size is None else max(min_group_size, rule.min_group_size)
    return Obligations(
        all_rows, tuple(rows), frozenset(hidden), frozenset(masked - hidden), min_group_size,
        tuple(r.name for r in matched),
    )


def _no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate field '{key}'")
        result[key] = value
    return result


def _string(raw: Any, field: str) -> str | None:
    """Jackson reads any scalar into a String field (``5`` → ``"5"``); objects and arrays it refuses."""
    if raw is None or isinstance(raw, str):
        return raw
    if isinstance(raw, bool):
        return "true" if raw else "false"
    if isinstance(raw, int | float):
        return str(raw)
    raise ValueError(f"Invalid rules JSON: {field} must be a string")


def _member[E: enum.StrEnum](kind: type[E], raw: Any, field: str) -> E:
    if not isinstance(raw, str) or raw not in {m.value for m in kind}:
        raise ValueError(f"Invalid rules JSON: {field}: {json.dumps(raw)} is not one of {[m.value for m in kind]}")
    return kind(raw)


def _rule(raw: Any) -> Rule:
    if not isinstance(raw, dict):
        raise ValueError("Invalid rules JSON: a rule is an object")
    for key in raw:
        if key not in _RULE_KEYS:
            raise ValueError(f"Invalid rules JSON: unrecognized field \"{key}\" in a rule")
    name = _string(raw.get("name"), "name")
    named = "null" if name is None else name  # how Java prints a missing name
    principals = raw.get("principals") or {}
    if not isinstance(principals, dict):
        raise ValueError("Invalid rules JSON: principals must be an object")
    principals = {k: _string(v, f"principals.{k}") for k, v in principals.items()}
    actions_raw = raw.get("actions") or []
    if not isinstance(actions_raw, list):
        raise ValueError("Invalid rules JSON: actions must be an array")
    actions = tuple(_member(Action, a, "actions") for a in actions_raw)
    effect = None if raw.get("effect") is None else _member(Effect, raw["effect"], "effect")
    row_filter = _string(raw.get("row_filter"), "row_filter")
    columns_raw = raw.get("columns") or {}
    if not isinstance(columns_raw, dict):
        raise ValueError("Invalid rules JSON: columns must be an object")
    columns = {k: _member(ColumnAction, v, f"columns.{k}") for k, v in columns_raw.items()}
    aggregation = raw.get("aggregation")
    min_group_size = None
    if aggregation is not None:
        if not isinstance(aggregation, dict) or set(aggregation) - {"min_group_size"}:
            raise ValueError("Invalid rules JSON: aggregation has only min_group_size")
        min_group_size = aggregation.get("min_group_size")
        if min_group_size is not None and (isinstance(min_group_size, bool) or not isinstance(min_group_size, int)):
            raise ValueError("Invalid rules JSON: aggregation.min_group_size must be an integer")
    # The record's checks, in Java's order.
    for key in principals:
        if key != "role":
            raise ValueError(f"rule {named}: unknown principals selector '{key}' (only 'role' is supported)")
    if effect is None:
        raise ValueError(f"rule {named}: effect is required (allow or deny)")
    role = principals.get("role")
    if role is None or not role.strip():
        raise ValueError(f"rule {named}: principals.role is required")
    if not actions:
        raise ValueError(f"rule {named}: actions must name read or aggregate")
    if effect == Effect.DENY and (row_filter is not None or columns or aggregation is not None):
        raise ValueError(
            f"rule {named}: a deny takes no row_filter, columns or aggregation; it drops every row for its"
            " role and actions"
        )
    if effect == Effect.ALLOW:
        RowFilter.parse(row_filter)  # fail at load time, not at query time
    if min_group_size is not None and min_group_size < 1:
        raise ValueError(f"rule {named}: aggregation.min_group_size must be at least 1")
    return Rule(name, role, actions, effect, row_filter, columns, min_group_size)


MAX_RULES_BYTES = 1024 * 1024  # a rules file is a few KiB; anything bigger is no rule set


def load_rule_sets(directory: Path, plugins: Sequence[Any]) -> dict[str, RuleSet]:
    """``rules/*.json`` of the extracted artifact with the checks Java applies to a jar's
    ``aisolate/rules/`` (``RuleSetFiles``) and ``CatalogCheck``'s rule-set part; ValueError naming the file."""
    found: dict[str, RuleSet] = {}
    if not directory.is_dir():
        return found
    for path in sorted(directory.rglob("*.json")):
        name = "rules/" + path.relative_to(directory).as_posix()
        if path.parent != directory:
            raise ValueError(f"{name}: a rule set is rules/<entity>.json")
        if path.stat().st_size > MAX_RULES_BYTES:
            raise ValueError(f"{name}: longer than 1 MiB")
        try:
            rule_set = RuleSet.parse(path.read_text(encoding="utf-8"))
        except ValueError as invalid:
            raise ValueError(f"{name}: {invalid}") from invalid
        if rule_set.entity in found:
            raise ValueError(f"two rule sets for entity {rule_set.entity}")
        found[rule_set.entity] = rule_set
        declared = next((e for p in plugins if (e := p.entity(rule_set.entity)) is not None), None)
        # A misspelt column would leave the column it meant in the clear; an undeclared entity is never reached.
        for rule in rule_set.rules if declared is not None else ():
            row_filter = RowFilter.parse(rule.row_filter) if rule.row_filter is not None else None
            named = list(rule.columns) + ([row_filter.column] if row_filter and not row_filter.matches_all else [])
            for column in named:
                if column not in declared.columns:
                    raise ValueError(
                        f"rule set {rule_set.entity}: rule {'null' if rule.name is None else rule.name} names column"
                        f" {column} no plugin declares"
                    )
    return found
