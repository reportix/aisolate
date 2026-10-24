"""The airlock's API on 8080 (``ChatController``, ``AirlockAdmissionService``, ``GlobalExceptionHandler``,
``SecurityConfig``): the chat endpoint as SSE or one JSON document, the RFC 9728 document, the errors in
Java's order: token 401, body 400, audit 503, airlock 404, admission 403."""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from http import HTTPStatus

from aisolate import AirlockRef
from aisolate import Principal as SdkPrincipal
from aisolate_runtime.identity import Unauthorized, bearer
from opentelemetry import context, trace
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.cors import CORSMiddleware
from starlette.requests import Request
from starlette.responses import Response, StreamingResponse
from starlette.routing import Route

from aisolate_airlock import audit, jsonw, tracing
from aisolate_airlock.answer_format import java_strip
from aisolate_airlock.exchange import MODEL_UNAVAILABLE, NO_MODEL_ENDPOINT, AgentLoop, Airlock, ExchangeFailed, Manager

log = logging.getLogger("aisolate_airlock.app")

MAX_QUESTION_LENGTH = 4000
CHAT_ROUTE = "/v1/spaces/{spaceId}/airlocks/{airlockId}/chat"
_SERVER_SPAN = "http post " + CHAT_ROUTE


@dataclass
class Settings:
    public_url: str
    issuer: str
    cors_origins: str = "*"


def problem(status: int, detail: str | None, instance: str) -> Response:
    body = {"type": "about:blank", "title": HTTPStatus(status).phrase, "status": status}
    if detail is not None:
        body["detail"] = detail
    body["instance"] = instance
    return Response(jsonw.dumps(body), status, media_type="application/problem+json")


def unauthorized(public_url: str) -> Response:
    return Response('{"status":401,"title":"Unauthorized"}', 401, media_type="application/problem+json",
                    headers={"WWW-Authenticate": f'Bearer resource_metadata="{public_url}'
                                                 '/.well-known/oauth-protected-resource"'})


def _java(value) -> str:
    return "null" if value is None else str(value)


def _message(body: bytes) -> str | None:
    """The validated message, None when blank, too long or not a request (Spring's @Valid, 400)."""
    try:
        request = json.loads(body)
    except ValueError:
        return None
    message = request.get("message") if isinstance(request, dict) else None
    if isinstance(message, bool):
        message = "true" if message else "false"  # Jackson coerces scalars into a String field
    elif isinstance(message, (int, float)):
        message = jsonw.dumps(message)
    # Java's String.length counts UTF-16 units.
    if (not isinstance(message, str) or not java_strip(message)
            or len(message.encode("utf-16-le")) // 2 > MAX_QUESTION_LENGTH):
        return None
    return message


class _Refused(Exception):
    def __init__(self, response: Response):
        self.response = response


# Named as the Java exceptions: their names are the spans' error.type.
class AuditUnavailableException(_Refused):
    pass


class AirlockNotFoundException(_Refused):
    pass


class ForbiddenException(_Refused):
    pass


def create_app(airlock: Airlock, agent: AgentLoop, verifier, audit_log: audit.AuditLog, settings: Settings,
               pools=None, llms=None) -> Starlette:
    """``verifier`` is ``aisolate_runtime.identity.TokenVerifier`` (or a fake with ``async verify``)."""

    async def principal_of(request: Request):
        try:
            return await verifier.verify(bearer(request.headers.get("authorization")))
        except Unauthorized:
            return None

    def admit(space_id: str, airlock_id: str, user, question: str, path: str, exchange_span) -> None:
        with tracing.span("admission") as span:
            if not audit_log.accepting():
                raise AuditUnavailableException(problem(503, "audit unavailable", path))
            began = datetime.now(UTC)
            started = time.monotonic()
            if (airlock_id, space_id) != (airlock.id, airlock.space):
                raise AirlockNotFoundException(problem(404, "unknown airlock", path))
            for target in (exchange_span, span):
                for key, value in tracing.describe(airlock).items():
                    tracing.tag(target, key, value)
            tracing.tag(exchange_span, "aisolate.client", user.azp)
            tracing.tag(span, "aisolate.admitted", True)
            reason = user.admission_failure(airlock.roles, airlock.callers, airlock.clients)
            if reason is not None:
                tracing.tag(span, "aisolate.admitted", False)
                tracing.tag(span, "aisolate.reason", reason)
                tracing.tag(exchange_span, "aisolate.outcome", "refused")
                audit_log.record(audit.refusal(began, tracing.trace_id(), airlock.space, airlock.id,
                                               airlock.revision, user.email or user.sub, user.azp, question, reason,
                                               int((time.monotonic() - started) * 1000)))
                # the reason, not the token's shape, picks the text: a machine client can be refused on its roles too
                who = (f"roles [{', '.join(sorted(user.roles))}] from client {_java(user.azp)}" if reason == "role"
                       else f"client {_java(user.azp)}")
                log.warning("403 POST %s: %s/%s does not admit %s", path, airlock.space, airlock.id, who)
                raise ForbiddenException(problem(403, "forbidden", path))

    def manager_for(user) -> Manager:
        return Manager(SdkPrincipal(user.sub, user.email, frozenset(user.roles)),
                       AirlockRef(airlock.space, airlock.id, airlock.revision), pools, llms)

    async def chat(request: Request) -> Response:
        path = request.url.path
        user = await principal_of(request)
        if user is None:
            return unauthorized(settings.public_url)
        message = _message(await request.body())
        if message is None:
            return problem(400, "message is blank or too long", path)
        p = request.path_params
        token = bearer(request.headers.get("authorization"))
        as_json = request.headers.get("accept", "").strip() == "application/json"
        # Every request starts a trace of its own: a caller's traceparent is never read.
        server_span = tracing.tracer.start_span(_SERVER_SPAN, context=context.Context(),
                                                attributes={"http.route": CHAT_ROUTE, "http.request.method": "POST"})
        exchange_span = tracing.tracer.start_span(
            f"exchange {p['spaceId']}/{p['airlockId']}", context=trace.set_span_in_context(server_span),
            attributes={tracing.SPACE: p["spaceId"], tracing.AIRLOCK: p["airlockId"]})

        def end(status: int, error: BaseException | None = None) -> None:
            if error is not None:
                tracing.failed(exchange_span, error)
            exchange_span.end()
            server_span.set_attribute("http.response.status_code", status)
            if status >= 500:
                server_span.set_status(trace.Status(trace.StatusCode.ERROR))
            server_span.end()

        attached = context.attach(trace.set_span_in_context(exchange_span))
        try:
            try:
                admit(p["spaceId"], p["airlockId"], user, message, path, exchange_span)
            except _Refused as refused:
                end(refused.response.status_code, refused)
                return refused.response
            manager = manager_for(user)
            if as_json:
                try:
                    answer = await agent.collect_answer(airlock, message, manager, user.azp, token, exchange_span)
                except ExchangeFailed as failed:
                    unavailable = failed.error in (MODEL_UNAVAILABLE, NO_MODEL_ENDPOINT)
                    end(503 if unavailable else 500, failed)
                    return problem(503 if unavailable else 500, str(failed), path)
                end(200)
                return Response(jsonw.dumps(answer), media_type="application/json")
            return _stream(agent, airlock, message, manager, user.azp, token, exchange_span, end)
        finally:
            context.detach(attached)

    async def protected_resource(request: Request) -> Response:
        return Response(jsonw.dumps({"resource": settings.public_url, "authorization_servers": [settings.issuer],
                                     "scopes_supported": ["aisolate"], "resource_name": "AIsolate",
                                     "tls_client_certificate_bound_access_tokens": False}),
                        media_type="application/json")

    async def anything_else(request: Request) -> Response:
        if await principal_of(request) is None:
            return unauthorized(settings.public_url)
        return problem(404, None, request.url.path)

    methods = ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"]
    return Starlette(
        routes=[Route(CHAT_ROUTE, chat, methods=["POST"]),
                Route("/.well-known/oauth-protected-resource", protected_resource, methods=["GET"]),
                Route("/{path:path}", anything_else, methods=methods)],
        middleware=[Middleware(_RevisionHeader, revision=airlock.revision),
                    Middleware(CORSMiddleware, allow_origin_regex=_origins(settings.cors_origins),
                               allow_methods=["GET", "POST", "PUT", "OPTIONS"],
                               allow_headers=["Authorization", "Content-Type", "Accept", "If-Match"],
                               expose_headers=["AIsolate-Cluster"], max_age=3600)])


def _stream(agent, airlock, message, manager, client, token, exchange_span, end) -> StreamingResponse:
    """The stream opens before the model is asked; a client that leaves stops the writes, never the
    exchange, which runs on to its audit line."""
    loop = asyncio.get_running_loop()
    queue: asyncio.Queue = asyncio.Queue()
    finished = object()

    def emit(event: dict) -> None:
        loop.call_soon_threadsafe(queue.put_nowait, event)

    async def run() -> None:
        try:
            await agent.answer_user_message(airlock, message, manager, client, emit, token, exchange_span)
        finally:
            end(200)
            loop.call_soon_threadsafe(queue.put_nowait, finished)

    task = asyncio.ensure_future(run())
    _RUNNING.add(task)
    task.add_done_callback(_RUNNING.discard)

    async def frames():
        while (event := await queue.get()) is not finished:
            data = {k: v for k, v in event.items() if not k.startswith("_")}
            yield f"event: {event['type']}\ndata: {jsonw.dumps(data)}\n\n".encode("utf-8")

    return StreamingResponse(frames(), media_type="text/event-stream;charset=UTF-8",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


_RUNNING: set[asyncio.Future] = set()


def _origins(patterns: str) -> str:
    """Spring's origin patterns as one regex: ``*`` matches anything, comma-separated."""
    parts = [p.strip() for p in patterns.split(",") if p.strip()] or ["*"]
    return "^(?:" + "|".join(re.escape(p).replace(r"\*", ".*") for p in parts) + ")$"


class _RevisionHeader:
    """``x-aisolate-revision`` on every response: which release answered."""

    def __init__(self, app, revision: str):
        self.app = app
        self.revision = revision.encode("latin-1")

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)

        async def with_revision(message):
            if message["type"] == "http.response.start":
                message["headers"] = [*message.get("headers", []), (b"x-aisolate-revision", self.revision)]
            await send(message)

        await self.app(scope, receive, with_revision)
