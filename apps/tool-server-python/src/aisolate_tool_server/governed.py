"""One tool call under the gates and the rules (Java: ``GovernedQueryService``, ``PolicySource``,
``Policy``, ``PluginCatalog``, ``GovernedToolsConfig``)."""

from __future__ import annotations

import datetime
import json
import logging
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Any, Protocol

from aisolate import Entity, Filter, Fn, Result, Tool, ToolPlugin
from aisolate.build import Catalog
from aisolate_runtime.config import Access, Configuration
from aisolate_runtime.identity import DEFAULT_ROLES_CLAIM, Principal
from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.trace import Span
from prometheus_client import Counter

from aisolate_tool_server import rules, sql
from aisolate_tool_server.rules import Action, RuleSet

log = logging.getLogger("aisolate.toolserver")
tracer = trace.get_tracer("aisolate.toolserver")

QUERIES = Counter(
    "aisolate_tool_queries", "Governed tool calls by outcome", ["airlock", "tool", "outcome"]
)


@dataclass(frozen=True, slots=True)
class Policy:
    airlock: str
    revision: str
    access: Access
    tools: tuple[str, ...]

    @property
    def rule_version(self) -> str:
        """What evidence names as what it was produced under: the revision of the documents."""
        return self.revision


class PolicySource:
    """Every served airlock's gate inputs, found only by a complete pin at this pod's revision."""

    def __init__(self, revision: str, policies: Sequence[Policy]) -> None:
        self.revision = revision
        self.policies = {p.airlock: p for p in policies}

    @classmethod
    def of(cls, configuration: Configuration) -> PolicySource:
        return cls(
            configuration.revision,
            [Policy(a.name, configuration.revision, a.access, a.tools) for a in configuration.airlocks.values()],
        )

    def find(self, meta: Mapping[str, Any] | None) -> Policy | None:
        """A pin is read only from JSON strings; anything else pins nothing."""
        pins = [meta.get(key) if meta is not None else None for key in ("airlock", "revision")]
        if not all(isinstance(pin, str) for pin in pins) or pins[1] != self.revision:
            return None
        return self.policies.get(pins[0])


class Database(Protocol):
    """The data sources as the governed query uses them."""

    def first_value(self, data_source: str, statement: str, params: Sequence[Any]) -> Any:
        """First column of the first row of a DB-API (``%s``) statement; None without a row."""
        ...

    def rows(self, data_source: str, statement: str, params: Sequence[Any], columns: Sequence[str]) -> list[dict[str, Any]]:
        """The rows of a compiled (``?``) statement, keyed by ``columns``."""
        ...


class QueryFailed(RuntimeError):
    """A statement failed; the model sees the generic error, never the driver's text."""


class GovernedQueries:
    """The tool server's domain: gates, plugin, identity join, rules, SQL."""

    def __init__(
        self,
        policies: PolicySource,
        catalog: Catalog,
        rule_sets: Mapping[str, RuleSet],
        database: Database,
        roles_claim: str = DEFAULT_ROLES_CLAIM,
    ) -> None:
        self.policies = policies
        self.catalog = catalog
        self.rule_sets = dict(rule_sets)
        self.database = database
        self.roles_claim = roles_claim
        self._tools = catalog.tools()

    def published(self) -> list[Tool]:
        """What ``tools/list`` advertises: every tool a served airlock lists and a plugin publishes, by name."""
        listed = {name for policy in self.policies.policies.values() for name in policy.tools}
        return sorted((t for name, (_, t) in self._tools.items() if name in listed), key=lambda t: t.name)

    def data_source_of(self, tool: str) -> str | None:
        found = self._tools.get(tool)
        return found[0].data_source if found else None

    def call(
        self,
        claims: Mapping[str, Any],
        meta: Mapping[str, Any] | None,
        tool: str,
        arguments: dict[str, Any],
        parent: Context | None = None,
    ) -> Result:
        """Runs one call; ValueError for arguments that do not fit (a tool error the model sees).
        ``parent``: the request's trace context (the call runs on a worker thread)."""
        policy = self.policies.find(meta)
        with tracer.start_as_current_span(f"governed query {tool}", context=parent) as span:
            for key in ("airlock", "revision"):
                pin = meta.get(key) if meta is not None else None
                _tag(span, f"aisolate.{key}", pin if isinstance(pin, str) else None)
            _tag(span, "aisolate.tool", tool)
            outcome = "error"
            try:
                result = self._answer(claims, policy, tool, arguments, span)
                _tag(span, "aisolate.entity", result.entity)
                _tag(span, "aisolate.rows", result.row_count)
                outcome = "rows" if result.row_count > 0 else "empty" if result.rules else "denied"
                return result
            except ValueError:
                outcome = "invalid"
                raise
            finally:
                _tag(span, "aisolate.outcome", outcome)
                # A caller cannot invent a label: the served policy's, else none.
                QUERIES.labels(policy.airlock if policy else "none", tool, outcome).inc()

    def _answer(self, claims: Mapping[str, Any], policy: Policy | None, tool: str, arguments: dict[str, Any], span: Span) -> Result:
        if policy is None:
            return Result.empty("", "")
        caller = Principal.of(claims, self.roles_claim)
        # Gate 2 again on this server's own reading of the token, then gate 3: no lookup, no query before.
        access = policy.access
        if caller.admission_failure(access.roles, access.callers, access.clients) is not None or tool not in policy.tools:
            return Result.empty("", policy.rule_version)
        plugin, published = self._tools[tool]
        _tag(span, "aisolate.data_source", plugin.data_source)
        data_source = plugin.data_source if plugin.entities else None
        email = claims.get("email")
        identity = None
        if data_source is not None and plugin.identity_join is not None and email is not None:
            identity = self._statement(lambda: self.database.first_value(data_source, plugin.identity_join, [email]))
        principal = Principal.of(claims, self.roles_claim, plugin.identity_attribute, identity)
        returned = published.handler(_Bound(self, policy, principal, plugin, data_source), arguments)
        if not isinstance(returned, Result):
            raise RuntimeError(f"plugin {plugin.name}: tool {tool} returned {type(returned).__name__}, not a Result")
        return returned

    @staticmethod
    def _statement(run: Callable[[], Any]) -> Any:
        try:
            return run()
        except Exception as failure:
            raise QueryFailed(type(failure).__name__) from failure


class _Bound:
    """The governed query one tool call may make, bound to its policy, principal and plugin."""

    def __init__(self, owner: GovernedQueries, policy: Policy, principal: Principal, plugin: ToolPlugin, data_source: str | None) -> None:
        self.owner, self.policy, self.principal, self.plugin, self.data_source = owner, policy, principal, plugin, data_source

    def read(self, entity: str, filters: Sequence[Filter] = (), limit: int = 100) -> Result:
        def compile_read(e: Entity, obligations: rules.Obligations) -> sql.CompiledQuery:
            args = _args(e, obligations).read(filters, limit)
            own = sql.own_key(e, self.plugin.identity_attribute, self.principal.attributes.get(self.plugin.identity_attribute or ""))
            return sql.compile_read(e, obligations, args, own)

        return self._run(entity, Action.READ, compile_read)

    def aggregate(self, entity: str, function: Fn, column: str, group_by: str | None = None, filters: Sequence[Filter] = ()) -> Result:
        def compile_aggregate(e: Entity, obligations: rules.Obligations) -> sql.CompiledQuery:
            return sql.compile_aggregate(e, obligations, _args(e, obligations).aggregate(function, column, group_by, filters))

        return self._run(entity, Action.AGGREGATE, compile_aggregate)

    def _run(self, name: str, action: Action, compile: Callable[[Entity, rules.Obligations], sql.CompiledQuery]) -> Result:
        # Reachable only through the plugin declaring it, and only where a rule set governs it.
        entity = self.plugin.entity(name)
        rule_set = self.owner.rule_sets.get(name)
        if entity is None or rule_set is None:
            log.warning(
                "%s: entity '%s' declared=%s governed=%s; asked by a tool of plugin %s",
                self.policy.airlock, name, entity is not None, rule_set is not None, self.plugin.name,
            )
            return Result.empty(name, self.policy.rule_version)
        obligations = rules.evaluate(self.principal, rule_set, action)
        if obligations is None:
            return Result.empty(name, self.policy.rule_version)
        query = compile(entity, obligations)
        with tracer.start_as_current_span(f"sql {self.plugin.data_source}") as span:
            for key, value in (
                ("airlock", self.policy.airlock), ("revision", self.policy.revision),
                ("data_source", self.plugin.data_source), ("entity", entity.name),
            ):
                _tag(span, f"aisolate.{key}", value)
            found = GovernedQueries._statement(
                lambda: self.owner.database.rows(self.data_source, query.sql, query.params, query.columns)
            )
        return Result(entity.name, len(found), query.columns, found, self.policy.rule_version,
                      obligations.matched_rules, query.sql_hash, query.labels)


def _args(entity: Entity, obligations: rules.Obligations) -> sql.ToolArgs:
    return sql.ToolArgs(entity, entity.queryable_columns(set(obligations.hidden), set(obligations.masked)))


def _tag(span: Span, key: str, value: Any) -> None:
    if value is not None and str(value) != "":
        span.set_attribute(key, value if isinstance(value, int) and not isinstance(value, bool) else str(value))


def result_json(result: Result, data_source: str | None) -> str:
    """The tool result as the Java tool server writes it: snake_case, nulls kept, ``data_source`` last,
    numbers exact (a Decimal is written as its digits, never through a float)."""
    labels = result.labels
    document = {
        "entity": result.entity,
        "row_count": result.row_count,
        "columns": list(result.columns),
        "rows": [dict(row) for row in result.rows],
        "rule_version": result.rule_version,
        "rules": list(result.rules),
        "sql_hash": result.sql_hash,
        "labels": None if labels is None else {
            "row_key": labels.row_key, "subject": labels.subject, "period": labels.period,
            "units": dict(labels.units), "unit_columns": dict(labels.unit_columns),
            "decimals": dict(labels.decimals), "masked": list(labels.masked), "function": labels.function,
            # Only when a fact column is readable, so a result without one is written as before.
            **({"facts": list(labels.facts)} if labels.facts else {}),
        },
        "data_source": data_source,
    }
    return _json(document)


def _json(value: Any) -> str:
    match value:
        case None:
            return "null"
        case bool():
            return "true" if value else "false"
        case int():
            return str(value)
        case Decimal():
            if not value.is_finite():
                raise ValueError(f"{value} is no JSON number")
            return str(value)
        case float():
            return json.dumps(value)
        case str():
            return json.dumps(value, ensure_ascii=False)
        case datetime.datetime():
            return json.dumps(value.isoformat())
        case datetime.date():
            return json.dumps(value.isoformat())
        case Mapping():
            return "{" + ",".join(f"{json.dumps(str(k), ensure_ascii=False)}:{_json(v)}" for k, v in value.items()) + "}"
        case list() | tuple():
            return "[" + ",".join(_json(v) for v in value) + "]"
    return json.dumps(str(value), ensure_ascii=False)


class PoolsDatabase:
    """The :class:`Database` over the pod's pools: the identity join on DB-API, statements on pg8000 native."""

    def __init__(self, pools: Any) -> None:
        self.pools = pools

    def first_value(self, data_source: str, statement: str, params: Sequence[Any]) -> Any:
        with self.pools.connect(data_source) as db:
            cursor = db.cursor()
            cursor.execute(statement, tuple(params))
            row = cursor.fetchone()
        return None if row is None else row[0]

    def rows(self, data_source: str, statement: str, params: Sequence[Any], columns: Sequence[str]) -> list[dict[str, Any]]:
        text, named = sql.for_driver(statement, params)
        with self.pools.native(data_source) as db:
            found = db.run(text, **named)
        return [dict(zip(columns, row, strict=True)) for row in found]
