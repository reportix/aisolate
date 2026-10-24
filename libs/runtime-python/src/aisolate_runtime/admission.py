"""Gate 2, the one place it is decided (Java: ``Principal.admissionFailure``)."""

from __future__ import annotations

from collections.abc import Collection
from typing import Literal

Failure = Literal["role", "client"]
"""Why gate 2 refused, as the audit line's ``reason`` writes it."""


def failure(
    email: str | None,
    azp: str | None,
    roles: Collection[str],
    allowed_roles: Collection[str],
    callers: Collection[str],
    clients: Collection[str] = (),
) -> Failure | None:
    """None for a machine client: no ``email`` and ``azp`` one of ``clients`` (exact; ``*`` is no
    wildcard there; roles and ``callers`` not consulted). Otherwise None when one of the token's
    ``roles`` is in ``allowed_roles`` (exact, case-sensitive; ``*`` is no wildcard; no e-mail needed)
    and ``azp`` one of ``callers`` (exact; ``*`` admits any present azp); else why not, the role
    looked at first."""
    if not isinstance(email, str) and isinstance(azp, str) and azp in clients:
        return None
    if not any(role in allowed_roles for role in roles):
        return "role"
    if not isinstance(azp, str) or ("*" not in callers and azp not in callers):
        return "client"
    return None


def admits(
    email: str | None,
    azp: str | None,
    roles: Collection[str],
    allowed_roles: Collection[str],
    callers: Collection[str],
    clients: Collection[str] = (),
) -> bool:
    return failure(email, azp, roles, allowed_roles, callers, clients) is None
