"""Bearer tokens and the principal (Java: libs/identity ``SecurityConfig``, ``Principal``)."""

from __future__ import annotations

import asyncio
import logging
import os
import time
from collections.abc import Collection, Mapping
from dataclasses import dataclass, field
from typing import Any

import httpx2
import jwt

from aisolate_runtime import admission

log = logging.getLogger("aisolate.identity")

DEFAULT_ROLES_CLAIM = "realm_access.roles"
LEEWAY_SECONDS = 60
"""Spring's resource server allows 60 s of clock skew on ``exp`` and ``nbf`` (PyJWT's default is 0)."""


class Unauthorized(Exception):
    """The token is missing or unusable: 401, whatever the reason (the reason is only logged)."""


@dataclass(frozen=True, slots=True)
class Principal:
    """The end user as the validated token describes them. ``attributes`` is what a row filter may
    compare with as ``principal.<name>``: ``sub``, ``email`` when present, and the plugin's identity
    attribute when the join found a row."""

    sub: str
    email: str | None
    azp: str | None
    roles: frozenset[str]
    attributes: Mapping[str, Any] = field(default_factory=dict)

    @classmethod
    def of(
        cls,
        claims: Mapping[str, Any],
        roles_claim: str = DEFAULT_ROLES_CLAIM,
        identity_attribute: str | None = None,
        identity_value: Any = None,
    ) -> Principal:
        """From validated claims; Unauthorized ``token has no sub claim`` without a non-blank ``sub``."""
        sub = claims.get("sub")
        if not isinstance(sub, str) or not sub.strip():
            raise Unauthorized("token has no sub claim")
        email = claims.get("email") if isinstance(claims.get("email"), str) else None
        azp = claims.get("azp") if isinstance(claims.get("azp"), str) else None
        attributes: dict[str, Any] = {"sub": sub}
        if email is not None:
            attributes["email"] = email
        if identity_attribute is not None and identity_value is not None:
            attributes[identity_attribute] = identity_value
        return cls(sub, email, azp, frozenset(roles(claims, roles_claim)), attributes)

    def with_attribute(self, name: str | None, value: Any) -> Principal:
        """The same principal with the identity join's value; unchanged when either is None."""
        if name is None or value is None:
            return self
        return Principal(self.sub, self.email, self.azp, self.roles, {**self.attributes, name: value})

    def admission_failure(
        self, users: Collection[str], callers: Collection[str], clients: Collection[str] = ()
    ) -> admission.Failure | None:
        return admission.failure(self.email, self.azp, users, callers, clients)


def roles(claims: Mapping[str, Any], roles_claim: str) -> list[str]:
    """The dot path through objects; a list keeps its string items, a string is one role, else none."""
    node: Any = claims
    for segment in roles_claim.split("."):
        if not isinstance(node, Mapping):
            return []
        node = node.get(segment)
    if isinstance(node, list):
        return [role for role in node if isinstance(role, str)]
    return [node] if isinstance(node, str) else []


def bearer(authorization: str | None) -> str | None:
    """The token of an ``Authorization: Bearer <jwt>`` header value; None without one."""
    if authorization is None:
        return None
    scheme, _, token = authorization.strip().partition(" ")
    token = token.strip()
    return token if scheme.lower() == "bearer" and token else None


class TokenVerifier:
    """Validates bearer tokens as both Java pods do: RS256 signature from the issuer's JWKS (keys by
    ``kid``; ``jwks_uri`` None = the issuer's discovery document's), ``iss`` exactly ``issuer``,
    ``aud`` containing ``audience``, ``exp``/``nbf`` with 60 s skew."""

    REFRESH_AFTER = 10.0  # an unknown kid refetches the JWKS at most this often (key rotation)

    def __init__(
        self,
        issuer: str,
        jwks_uri: str | None,
        audience: str,
        roles_claim: str = DEFAULT_ROLES_CLAIM,
        http: httpx2.AsyncClient | None = None,
    ) -> None:
        """ValueError when ``issuer`` or ``audience`` is blank (Java: ``SecurityConfig.requireAudience``)."""
        if not issuer or not issuer.strip():
            raise ValueError("AISOLATE_ISSUER_URI is required: the issuer of the users' tokens")
        if not audience or not audience.strip():
            raise ValueError("the token audience is required: an empty audience would accept any token")
        self.issuer, self.jwks_uri, self.audience, self.roles_claim = issuer, jwks_uri, audience, roles_claim
        self._http = http or httpx2.AsyncClient(timeout=httpx2.Timeout(10.0, connect=5.0))
        self._keys: dict[str | None, Any] = {}
        self._fetched = 0.0
        self._lock = asyncio.Lock()

    @classmethod
    def from_env(cls, audience: str = "aisolate", env: Mapping[str, str] = os.environ) -> TokenVerifier:
        """``AISOLATE_ISSUER_URI``, ``SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI``,
        ``AISOLATE_ROLES_CLAIM`` (default ``realm_access.roles``)."""
        return cls(
            env.get("AISOLATE_ISSUER_URI", ""),
            env.get("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI") or None,
            audience,
            env.get("AISOLATE_ROLES_CLAIM") or DEFAULT_ROLES_CLAIM,
        )

    async def claims(self, token: str | None) -> dict[str, Any]:
        """The validated claims; Unauthorized otherwise. Fetches the JWKS on first use and again for
        an unknown ``kid``."""
        if not token:
            raise Unauthorized("no bearer token")
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as malformed:
            raise Unauthorized(type(malformed).__name__) from malformed
        if header.get("alg") != "RS256":
            raise Unauthorized(f"algorithm {header.get('alg')} is not RS256")
        kid = header.get("kid")
        keys = await self._keys_for(kid)
        if not keys:
            raise Unauthorized("no key of the issuer's JWKS signed it")
        failure: Exception | None = None
        for key in keys:
            try:
                claims = jwt.decode(
                    token, key, algorithms=["RS256"], audience=self.audience, issuer=self.issuer,
                    leeway=LEEWAY_SECONDS, options={"verify_iat": False, "require": ["iss", "aud"]},
                )
            except jwt.PyJWTError as invalid:
                failure = invalid
                continue
            exp, iat = claims.get("exp"), claims.get("iat")
            # Spring builds its Jwt only when exp is after iat; any other token is refused.
            if isinstance(exp, int | float) and isinstance(iat, int | float) and exp <= iat:
                raise Unauthorized("expiresAt must be after issuedAt")
            return claims
        raise Unauthorized(type(failure).__name__)

    async def verify(self, token: str | None) -> Principal:
        """The principal of a valid token; Unauthorized otherwise (no ``sub`` included)."""
        return Principal.of(await self.claims(token), self.roles_claim)

    async def _keys_for(self, kid: str | None) -> list[Any]:
        async with self._lock:
            if not self._keys or (kid not in self._keys and time.monotonic() - self._fetched > self.REFRESH_AFTER):
                await self._fetch()
        if kid is not None:
            return [self._keys[kid]] if kid in self._keys else []
        return list(self._keys.values())

    async def _fetch(self) -> None:
        self._fetched = time.monotonic()
        try:
            uri = self.jwks_uri
            if uri is None:
                discovery = await self._http.get(self.issuer.rstrip("/") + "/.well-known/openid-configuration")
                discovery.raise_for_status()
                uri = discovery.json()["jwks_uri"]
            response = await self._http.get(uri)
            response.raise_for_status()
            keys = {}
            for jwk in response.json().get("keys", []):
                if jwk.get("kty") == "RSA" and jwk.get("use", "sig") == "sig" and jwk.get("alg", "RS256") == "RS256":
                    keys[jwk.get("kid")] = jwt.PyJWK(jwk, "RS256").key
            self._keys = keys
        except Exception as unreachable:
            log.warning("the issuer's JWKS could not be read: %s", type(unreachable).__name__)
