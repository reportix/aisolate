"""A real tool server on a free port, a signing key and the JWKS it trusts."""

from __future__ import annotations

import base64
import json
import socket
import threading
import time
from typing import Any

import httpx2
import jwt
import uvicorn
from cryptography.hazmat.primitives.asymmetric import rsa

from aisolate_runtime.identity import TokenVerifier

ISSUER = "https://idp.example/realms/conformance"
CLAIMS = {"iss": ISSUER, "aud": "aisolate", "sub": "u-1", "email": "nora@example.com", "azp": "chat", "iat": -10, "exp": 300,
          "realm_access": {"roles": ["staff"]}}
"""A staff user's valid token claims (times relative to now, as :func:`token` reads them)."""
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def _b64(number: int) -> str:
    return base64.urlsafe_b64encode(number.to_bytes((number.bit_length() + 7) // 8, "big")).rstrip(b"=").decode()


def jwks() -> dict[str, Any]:
    numbers = KEY.public_key().public_numbers()
    return {"keys": [{"kty": "RSA", "kid": "conformance", "alg": "RS256", "use": "sig", "n": _b64(numbers.n), "e": _b64(numbers.e)}]}


def verifier(issuer: str = ISSUER) -> TokenVerifier:
    def answer(request: httpx2.Request) -> httpx2.Response:
        if request.url.path.endswith("/.well-known/openid-configuration"):
            return httpx2.Response(200, json={"issuer": issuer, "jwks_uri": "https://idp.example/certs"})
        return httpx2.Response(200, json=jwks())

    return TokenVerifier(issuer, None, "aisolate", http=httpx2.AsyncClient(transport=httpx2.MockTransport(answer)))


def token(claims: dict[str, Any], signed_by: str = "issuer_key") -> str:
    now = int(time.time())
    timed = {k: (now + v if k in ("iat", "exp", "nbf") and isinstance(v, int | float) else v) for k, v in claims.items()}
    if signed_by == "issuer_key":
        return jwt.encode(timed, KEY, algorithm="RS256", headers={"kid": "conformance"})
    if signed_by == "other_key":
        return jwt.encode(timed, OTHER_KEY, algorithm="RS256", headers={"kid": "conformance"})
    if signed_by == "none":
        header = base64.urlsafe_b64encode(b'{"alg":"none","typ":"JWT"}').rstrip(b"=").decode()
        body = base64.urlsafe_b64encode(json.dumps(timed).encode()).rstrip(b"=").decode()
        return f"{header}.{body}."
    if signed_by == "hs256_public":
        from cryptography.hazmat.primitives import serialization

        public = KEY.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
        import hashlib
        import hmac

        header = base64.urlsafe_b64encode(b'{"alg":"HS256","typ":"JWT","kid":"conformance"}').rstrip(b"=").decode()
        body = base64.urlsafe_b64encode(json.dumps(timed).encode()).rstrip(b"=").decode()
        mac = hmac.new(public, f"{header}.{body}".encode(), hashlib.sha256).digest()
        return f"{header}.{body}." + base64.urlsafe_b64encode(mac).rstrip(b"=").decode()
    raise ValueError(signed_by)


class Running:
    """``with Running(app) as url:`` serves ``app`` on 127.0.0.1 in a thread."""

    def __init__(self, app: Any) -> None:
        self.server = uvicorn.Server(uvicorn.Config(app, log_level="warning"))
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))

    def __enter__(self) -> str:
        self.thread = threading.Thread(target=self.server.run, kwargs={"sockets": [self.sock]}, daemon=True)
        self.thread.start()
        while not self.server.started:
            time.sleep(0.01)
        return f"http://127.0.0.1:{self.sock.getsockname()[1]}"

    def __exit__(self, *exc: object) -> None:
        self.server.should_exit = True
        self.thread.join(5)
