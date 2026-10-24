import pytest
from aisolate_runtime.identity import Principal

from conftest import read_case

CASES = read_case("admission", "airlock.json")


def _access(airlock: str) -> tuple[list[str], list[str], list[str]]:
    """``Configuration.accessOf``: the space's roles then the airlock's own, distinct, in order."""
    own = CASES["airlocks"][airlock]
    roles = list(dict.fromkeys([*CASES["space"]["roles"], *own["roles"]]))
    return roles, own["callers"], own.get("clients", [])


@pytest.mark.parametrize("case", CASES["cases"], ids=[c["id"] for c in CASES["cases"]])
def test_given_claims_when_the_airlock_admits_then_as_java(case):
    # Arrange
    roles, callers, clients = _access(case["airlock"])
    principal = Principal.of(case["claims"], "realm_access.roles")

    # Act
    reason = principal.admission_failure(roles, callers, clients)

    # Assert
    assert (reason is None) == case["expect"]["admitted"]
    assert reason == case["expect"].get("reason")
