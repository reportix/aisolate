"""What a governed tool returns, as the airlock reads the tool server's JSON (Java: ``ToolEvidence``)."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any, ClassVar


@dataclass(frozen=True)
class Labels:
    row_key: str | None = None
    subject: str | None = None
    period: str | None = None
    units: dict[str, str] = field(default_factory=dict)
    unit_columns: dict[str, str] = field(default_factory=dict)
    decimals: dict[str, int] = field(default_factory=dict)
    masked: list[str] = field(default_factory=list)
    function: str | None = None
    facts: list[str] = field(default_factory=list)

    NONE: ClassVar[Labels]

    @staticmethod
    def of(node: Any) -> Labels:
        if not isinstance(node, dict):
            return Labels.NONE
        return Labels(
            row_key=node.get("row_key"),
            subject=node.get("subject"),
            period=node.get("period"),
            units=node.get("units") or {},
            unit_columns=node.get("unit_columns") or {},
            decimals={k: int(v) for k, v in (node.get("decimals") or {}).items()},
            masked=node.get("masked") or [],
            function=node.get("function"),
            facts=node.get("facts") or [],
        )


Labels.NONE = Labels()


@dataclass(frozen=True)
class ToolEvidence:
    entity: str | None
    row_count: int
    columns: list[str]
    rows: list[dict[str, Any]]
    rule_version: str | None
    rules: list[str] | None
    sql_hash: str | None
    data_source: str | None
    labels: Labels

    @staticmethod
    def parse(text: str) -> ToolEvidence:
        """Raises ValueError for anything but a JSON object; numbers stay exact decimals."""
        node = json.loads(text, parse_float=Decimal, parse_constant=_no_constant)
        if not isinstance(node, dict):
            raise ValueError("not a JSON object")
        return ToolEvidence(
            entity=node.get("entity"),
            row_count=int(node.get("row_count") or 0),
            columns=node.get("columns") or [],
            rows=node.get("rows") or [],
            rule_version=node.get("rule_version"),
            rules=node.get("rules"),
            sql_hash=node.get("sql_hash"),
            data_source=node.get("data_source"),
            labels=Labels.of(node.get("labels")),
        )


def _no_constant(name: str) -> Any:
    # Jackson refuses NaN and Infinity; so do we.
    raise ValueError(f"not JSON: {name}")
