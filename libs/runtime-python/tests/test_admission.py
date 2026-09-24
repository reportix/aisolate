from typing import Any

import pytest
from conftest import case_file

from aisolate_runtime import admission
from aisolate_runtime.identity import Principal, Unauthorized, bearer, roles

AIRLOCK = case_file("admission/airlock.json")


def _access(airlock: str) -> tuple[list[str], list[str], list[str]]:
    own = AIRLOCK["airlocks"][airlock]
    users = list(dict.fromkeys(AIRLOCK["space"]["users"] + own["users"]))
    return users, own["callers"], own.get("clients", [])


@pytest.mark.parametrize("case", AIRLOCK["cases"], ids=[c["id"] for c in AIRLOCK["cases"]])
def test_given_conformance_claims_when_admission_decided_then_as_java(case: dict[str, Any]) -> None:
    # Arrange
    users, callers, clients = _access(case["airlock"])
    principal = Principal.of(case["claims"], "realm_access.roles")

    # Act
    failure = principal.admission_failure(users, callers, clients)

    # Assert
    assert failure is None if case["expect"]["admitted"] else failure == case["expect"]["reason"]


@pytest.mark.parametrize("sub", [None, "", "  ", 7])
def test_given_no_usable_sub_when_principal_made_then_unauthorized(sub: Any) -> None:
    # Arrange
    claims = {"email": "a@example.com"} | ({} if sub is None else {"sub": sub})

    # Act / Assert
    with pytest.raises(Unauthorized, match="token has no sub claim"):
        Principal.of(claims)


@pytest.mark.parametrize(
    ("claims", "expected"),
    [
        ({"realm_access": {"roles": ["a", 1, "b"]}}, ["a", "b"]),
        ({"realm_access": {"roles": "a"}}, ["a"]),
        ({"realm_access": {"roles": {"a": 1}}}, []),
        ({"realm_access": ["roles"]}, []),
        ({}, []),
    ],
)
def test_given_roles_claim_shapes_when_read_then_strings_only(claims: dict[str, Any], expected: list[str]) -> None:
    # Arrange / Act / Assert
    assert roles(claims, "realm_access.roles") == expected


def test_given_identity_value_when_principal_made_then_attributes_hold_it() -> None:
    # Arrange / Act
    principal = Principal.of({"sub": "u", "email": "e@x", "azp": 3}, identity_attribute="employee_id", identity_value=7)

    # Assert
    assert principal.attributes == {"sub": "u", "email": "e@x", "employee_id": 7}
    assert principal.azp is None


@pytest.mark.parametrize(
    ("header", "expected"), [("Bearer abc", "abc"), ("bearer  abc ", "abc"), ("Basic abc", None), ("Bearer ", None), (None, None)]
)
def test_given_authorization_header_when_bearer_read_then_token_or_none(header: str | None, expected: str | None) -> None:
    # Arrange / Act / Assert
    assert bearer(header) == expected


def test_given_star_among_users_when_admission_decided_then_no_wildcard() -> None:
    # Arrange / Act / Assert
    assert admission.failure("x@example.com", "app", ["*"], ["*"]) == "user"
