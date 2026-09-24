"""Gate 2, the one place it is decided (Java: ``Principal.admissionFailure``)."""

from __future__ import annotations

from collections.abc import Collection
from typing import Literal

Failure = Literal["user", "client"]
"""Why gate 2 refused, as the audit line's ``reason`` writes it."""


def failure(
    email: str | None, azp: str | None, users: Collection[str], callers: Collection[str], clients: Collection[str] = ()
) -> Failure | None:
    """None for a machine client: no ``email`` and ``azp`` one of ``clients`` (exact; ``*`` is no
    wildcard there; ``callers`` not consulted). Otherwise None when ``email`` is one of ``users``
    (case ignored, untrimmed; ``*`` is no wildcard there) and ``azp`` one of ``callers`` (exact;
    ``*`` admits any present azp); else why not, the user looked at first."""
    if not isinstance(email, str) and isinstance(azp, str) and azp in clients:
        return None
    if not isinstance(email, str) or not any(email.lower() == user.lower() for user in users):
        return "user"
    if not isinstance(azp, str) or ("*" not in callers and azp not in callers):
        return "client"
    return None


def admits(
    email: str | None, azp: str | None, users: Collection[str], callers: Collection[str], clients: Collection[str] = ()
) -> bool:
    return failure(email, azp, users, callers, clients) is None
