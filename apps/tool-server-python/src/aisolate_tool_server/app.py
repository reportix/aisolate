"""The MCP endpoint: ``POST /mcp`` on 8081, streamable HTTP, stateless, JSON responses (Java:
``GovernedToolsConfig``, ``SecurityConfig.mcpSecurity``)."""

from __future__ import annotations

import errno
import logging
import socket
from typing import Any

import anyio.to_thread
import mcp_types as types
from aisolate_runtime.config import Configuration
from aisolate_runtime.identity import TokenVerifier, Unauthorized, bearer
from mcp.server.lowlevel import Server
from mcp.server.transport_security import TransportSecuritySettings
from mcp.shared.exceptions import MCPError
from opentelemetry import context as otel_context
from opentelemetry import trace
from opentelemetry.propagate import extract
from opentelemetry.trace import SpanKind
from starlette.applications import Starlette
from starlette.types import ASGIApp, Receive, Scope, Send

from aisolate_tool_server.governed import GovernedQueries, result_json

log = logging.getLogger("aisolate.toolserver")
tracer = trace.get_tracer("aisolate.toolserver")

GENERIC_ERROR = "The tool could not complete the request"
# Why a tool call could not read its data: the model's sentence by the code the airlock passes on (Java:
# ToolError; conformance/cases/errors/data-source.json).
ERROR_TEXTS = {
    "login_refused": "The data source refused the login",
    "unreachable": "The data source could not be reached",
    "failed": GENERIC_ERROR,
}
_NETWORK = (ConnectionError, socket.gaierror, socket.herror, TimeoutError)
_NO_ROUTE = (errno.EHOSTUNREACH, errno.ENETUNREACH)
CLAIMS = "aisolate.claims"
TRACE = "aisolate.trace"
LOCAL_HOSTS = ("127.0.0.1", "localhost", "[::1]")


def service_hosts(configuration: Configuration) -> list[str]:
    """The hosts the airlock reaches this pod by: each served airlock's Service (``Names.mcpServerName``)."""
    return sorted({f"{a.space}-{a.name}-mcp-server" for a in configuration.airlocks.values()})


def create_app(queries: GovernedQueries, verifier: TokenVerifier, hosts: list[str] | None = None) -> ASGIApp:
    """``hosts``: the Host names (any port) besides localhost that the MCP endpoint answers; any other is 421."""
    listed = [
        types.Tool.model_validate({"name": t.name, "description": t.description, "inputSchema": dict(t.input_schema)})
        for t in queries.published()
    ]
    names = {t.name for t in listed}

    async def list_tools(ctx: Any, params: Any) -> types.ListToolsResult:
        return types.ListToolsResult(tools=listed)

    async def call_tool(ctx: Any, params: types.CallToolRequestParams) -> types.CallToolResult:
        if params.name not in names:
            raise MCPError(types.INVALID_PARAMS, f"Unknown tool: {params.name}")
        state = ctx.request.scope["state"]
        try:
            result = await anyio.to_thread.run_sync(
                queries.call, state[CLAIMS], params.meta, params.name, dict(params.arguments or {}), state[TRACE]
            )
            text, is_error = result_json(result, queries.data_source_of(params.name)), False
        except ValueError as invalid:
            text, is_error = f"Invalid arguments: {invalid}", True
        except Exception as failure:
            # Never let a driver message (which embeds the statement) reach the model: a fixed sentence,
            # and for the airlock what kind of failure it was and on which data source.
            error = error_of(failure)
            log.exception("tool %s failed: %s", params.name, error)
            meta = {"error": error, "data_source": queries.data_source_of(params.name)}
            return types.CallToolResult(content=[types.TextContent(type="text", text=ERROR_TEXTS[error])],
                                        is_error=True, meta=meta)
        return types.CallToolResult(content=[types.TextContent(type="text", text=text)], is_error=is_error)

    server = Server("aisolate-tool-server", version="0.1.0", on_list_tools=list_tools, on_call_tool=call_tool)
    # The SDK's own span records exception messages; this server's spans never carry one.
    server.middleware = []
    allowed = [*LOCAL_HOSTS, *(hosts or [])]
    security = TransportSecuritySettings(
        allowed_hosts=[f"{h}:*" for h in allowed] + list(allowed),
        allowed_origins=[f"http://{h}:*" for h in LOCAL_HOSTS],
    )
    mcp_app: Starlette = server.streamable_http_app(
        streamable_http_path="/mcp", json_response=True, stateless_http=True, transport_security=security
    )
    return _BearerOnly(mcp_app, verifier)


def error_of(failure: BaseException) -> str:
    """``login_refused`` for a SQLSTATE of class 28 anywhere in the failure; else ``unreachable`` for one of
    class 08 or a network failure; else ``failed``. pg8000 raises the server's error fields as a dict and
    re-raises a socket's failure from it."""
    found, seen, todo = "failed", set(), [failure]
    while todo:
        cause = todo.pop()
        if id(cause) in seen:
            continue
        seen.add(id(cause))
        fields = cause.args[0] if cause.args and isinstance(cause.args[0], dict) else {}
        state = str(fields.get("C", ""))
        if state.startswith("28"):
            return "login_refused"
        if (state.startswith("08") or isinstance(cause, _NETWORK)
                or isinstance(cause, OSError) and cause.errno in _NO_ROUTE):
            found = "unreachable"
        todo += [c for c in (cause.__cause__, cause.__context__) if c is not None]
    return found


class _BearerOnly:
    """Every request needs a valid user token, else 401 with ``WWW-Authenticate: Bearer`` and no body.
    The airlock's ``traceparent`` continues its trace."""

    def __init__(self, app: ASGIApp, verifier: TokenVerifier) -> None:
        self.app, self.verifier = app, verifier

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = {k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope["headers"]}
        try:
            claims = await self.verifier.claims(bearer(headers.get("authorization")))
            if not isinstance(claims.get("sub"), str) or not claims["sub"].strip():
                raise Unauthorized("token has no sub claim")
        except Unauthorized as refused:
            log.debug("401 %s %s: %s", scope.get("method"), scope.get("path"), refused)
            await send({"type": "http.response.start", "status": 401,
                        "headers": [(b"www-authenticate", b"Bearer"), (b"content-length", b"0")]})
            await send({"type": "http.response.body", "body": b""})
            return
        parent = extract({k: headers[k] for k in ("traceparent", "tracestate") if k in headers})
        with tracer.start_as_current_span(f"http {scope.get('method', '').lower()} {scope.get('path')}",
                                          context=parent, kind=SpanKind.SERVER):
            state = scope.setdefault("state", {})
            state[CLAIMS] = claims
            state[TRACE] = otel_context.get_current()
            await self.app(scope, receive, send)
