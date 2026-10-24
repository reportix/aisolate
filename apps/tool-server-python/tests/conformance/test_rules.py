import json
from typing import Any

import pytest
from conftest import RecordingDatabase, case_file, decimals, entity_of, result_dict, seed, standard_plugin

from aisolate.build import Catalog
from aisolate_runtime.config import Access
from aisolate_runtime.datasources import Login, Pools
from aisolate_runtime.identity import Principal
from aisolate_tool_server import rules
from aisolate_tool_server.governed import GovernedQueries, Policy, PolicySource, PoolsDatabase
from aisolate_tool_server.rules import Action, RuleSet

FILES = [case_file("rules/compensation.json"), case_file("rules/vitals.json")]
CASES = [(f, c) for f in FILES for c in f["cases"]]
REVISION = "0123456789ab"
META = {"airlock": "conformance", "revision": REVISION}


def _claims(principal: dict[str, Any]) -> dict[str, Any]:
    claims = {"sub": principal["sub"], "azp": "conformance-chat", "realm_access": {"roles": principal["roles"]}}
    return claims | ({"email": principal["email"]} if principal.get("email") is not None else {})


def _service(document: dict[str, Any], case: dict[str, Any], database: Any) -> GovernedQueries:
    fixture = document["fixture"]
    entity = entity_of(fixture["entity"])
    plugin = standard_plugin(entity, "conformance", fixture["identity_attribute"], "SELECT 'the identity join'")
    policy = Policy("conformance", REVISION, Access(tuple(case["principal"]["roles"]), ("*",)),
                    ("read_records", "aggregate_records"))
    rule_set = RuleSet.parse(json.dumps(document["rule_sets"][case["rule_set"]]))
    return GovernedQueries(PolicySource(REVISION, [policy]), Catalog("conformance", (plugin,), ()),
                           {entity.name: rule_set}, database)


def _call(document: dict[str, Any], case: dict[str, Any], database: Any) -> Any:
    service = _service(document, case, database)
    tool = "read_records" if case["query"]["action"] == "read" else "aggregate_records"
    try:
        return service.call(_claims(case["principal"]), META, tool, dict(case["query"]["args"]))
    except ValueError as invalid:
        return f"Invalid arguments: {invalid}"


def _identity(document: dict[str, Any], case: dict[str, Any]) -> Any:
    return case["principal"].get("attributes", {}).get(document["fixture"]["identity_attribute"])


@pytest.mark.parametrize(("document", "case"), CASES, ids=[c["id"] for _, c in CASES])
def test_given_rules_case_when_tool_called_then_obligations_statement_and_result_as_java(
    document: dict[str, Any], case: dict[str, Any]
) -> None:
    # Arrange
    expect = case["expect"]
    database = RecordingDatabase(identity=_identity(document, case))
    principal = Principal.of(_claims(case["principal"]), identity_attribute=document["fixture"]["identity_attribute"],
                             identity_value=_identity(document, case))
    rule_set = RuleSet.parse(json.dumps(document["rule_sets"][case["rule_set"]]))

    # Act
    outcome = _call(document, case, database)
    obligations = rules.evaluate(principal, rule_set, Action(case["query"]["action"]))

    # Assert
    if "error" in expect:
        assert outcome == expect["error"]
        assert database.statements == []
        return
    if expect["obligations"] is None:
        assert obligations is None
        assert result_dict(outcome) == expect["result"]
        assert database.statements == []
        return
    assert {
        "all_rows": obligations.all_rows,
        "rows": [{"column": r.column, "value": r.value} for r in obligations.rows],
        "hidden": sorted(obligations.hidden),
        "masked": sorted(obligations.masked),
        "min_group_size": obligations.min_group_size,
        "matched_rules": list(obligations.matched_rules),
    } == decimals(expect["obligations"]) | {"rows": expect["obligations"]["rows"]}
    [(_, statement, params)] = database.statements
    assert statement == expect["sql"]
    assert decimals(params) == decimals(expect["params"])
    produced = result_dict(outcome)
    assert produced["sql_hash"] == expect["sql_hash"] == expect["result"]["sql_hash"]
    for key in ("entity", "columns", "rule_version", "rules", "labels"):
        assert produced[key] == expect["result"][key], key


@pytest.mark.parametrize(
    ("document", "case"),
    [(d, c) for d, c in CASES if "sql" in c["expect"]],
    ids=[c["id"] for _, c in CASES if "sql" in c["expect"]],
)
def test_given_seeded_postgres_when_statement_runs_then_rows_as_java(
    postgres: Any, document: dict[str, Any], case: dict[str, Any]
) -> None:
    # Arrange
    if postgres is None:
        pytest.skip("no docker for Postgres")
    seed(postgres, document["fixture"]["table"])
    host, port, user, password = postgres
    pools = Pools({"conformance": Login("conformance", f"jdbc:postgresql://{host}:{port}/postgres", user, password)})

    class Identity(PoolsDatabase):
        def first_value(self, data_source: str, statement: str, params: Any) -> Any:
            return _identity(document, case)

    # Act
    try:
        outcome = _call(document, case, Identity(pools))
    finally:
        pools.close()

    # Assert
    expected = decimals(case["expect"]["result"]["rows"])
    produced = decimals(result_dict(outcome)["rows"])
    if case["query"]["action"] == "aggregate":
        key = lambda row: json.dumps(row, default=str, sort_keys=True)  # noqa: E731
        assert sorted(produced, key=key) == sorted(expected, key=key)
    else:
        assert produced == expected
    assert result_dict(outcome)["row_count"] == case["expect"]["result"]["row_count"]
