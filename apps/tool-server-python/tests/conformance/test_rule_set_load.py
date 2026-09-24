from typing import Any

import pytest
from conftest import case_file, ids

from aisolate_tool_server.rules import RuleSet

CASES = case_file("rules/rule-set-load.json")


@pytest.mark.parametrize("case", CASES["cases"], ids=ids(CASES))
def test_given_rule_set_file_when_parsed_then_loads_or_refuses_as_java(case: dict[str, Any]) -> None:
    # Arrange
    expect = case["expect"]

    # Act
    try:
        RuleSet.parse(case["json"])
        outcome = {"valid": True}
    except ValueError as refused:
        outcome = {"message": str(refused)}

    # Assert
    if expect.get("valid"):
        assert outcome == {"valid": True}
    elif "error" in expect:
        assert outcome == {"message": expect["error"]}
    else:
        assert outcome.get("message", "").startswith(expect["error_prefix"])
