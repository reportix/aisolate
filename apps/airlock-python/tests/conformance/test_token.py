import base64
import hashlib
import hmac
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import jwt
import pytest
from aisolate_runtime.identity import TokenVerifier
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from support import AIRLOCK, harness

from conftest import read_case

pytestmark = pytest.mark.anyio
CASES = read_case("admission", "token.json")
ISSUER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def _jwks_server() -> str:
    jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(ISSUER_KEY.public_key()))
    body = json.dumps({"keys": [{**jwk, "kid": "conformance", "alg": "RS256", "use": "sig"}]}).encode()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{server.server_port}/certs"


JWKS = _jwks_server()


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _bearer(case) -> str | None:
    if "raw" in case:
        return case["raw"]
    if case["signed_by"] is None:
        return None
    now = int(time.time())
    claims = {**CASES["base_claims"], **case["claims"]}
    claims = {k: (now + int(v) if k in ("iat", "exp", "nbf") else v) for k, v in claims.items() if v is not None}
    signed_by = case["signed_by"]
    if signed_by in ("issuer_key", "other_key"):
        key = ISSUER_KEY if signed_by == "issuer_key" else OTHER_KEY
        return jwt.encode(claims, key, algorithm="RS256", headers={"kid": "conformance"})
    algorithm = "none" if signed_by == "none" else "HS256"
    signing_input = _b64(json.dumps({"alg": algorithm, "kid": "conformance", "typ": "JWT"}).encode()) + "." + _b64(
        json.dumps(claims).encode())
    if signed_by == "none":
        return signing_input + "."
    public = ISSUER_KEY.public_key().public_bytes(serialization.Encoding.PEM,
                                                  serialization.PublicFormat.SubjectPublicKeyInfo)
    return signing_input + "." + _b64(hmac.new(public, signing_input.encode(), hashlib.sha256).digest())


@pytest.mark.parametrize("case", CASES["cases"], ids=[c["id"] for c in CASES["cases"]])
async def test_given_bearer_when_chat_then_valid_or_401_with_the_challenge(case):
    # Arrange: an airlock this pod does not serve, so a valid token stops at 404, never the model
    verifier = TokenVerifier(CASES["issuer"], JWKS, CASES["audience"])
    client, out = harness(None, verifier=verifier, airlock=AIRLOCK)
    token = _bearer(case)
    headers = {"Accept": "application/json", **({"Authorization": f"Bearer {token}"} if token is not None else {})}

    # Act
    response = await client.post("/v1/spaces/none/airlocks/none/chat", json={"message": "hi"},
                                 headers=headers)

    # Assert
    if case["expect"] == "valid":
        assert response.status_code == 404
    else:
        assert response.status_code == 401
        assert response.text == '{"status":401,"title":"Unauthorized"}'
        assert response.headers["www-authenticate"] == (
            'Bearer resource_metadata="http://aisolate.localhost/.well-known/oauth-protected-resource"')
    assert out.getvalue() == ""
