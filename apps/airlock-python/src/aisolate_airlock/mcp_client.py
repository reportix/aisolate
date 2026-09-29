"""The airlock's tool server over MCP streamable HTTP (``McpSyncToolClient``): stateless, one JSON-RPC POST
per call, each carrying the asking user's bearer, the ``_meta`` pin of the admitted airlock and the W3C trace
of the ``tool <name>`` span. Every transport failure becomes one of two fixed sentences: the SDK's errors
quote headers and bodies."""

from __future__ import annotations

import itertools
import json
import logging
from dataclasses import dataclass
from urllib.parse import urlsplit

import httpx2

from aisolate_airlock import jsonw, tracing

log = logging.getLogger("aisolate_airlock.mcp")

REJECTED = "the tool server rejected the request"
UNREACHABLE = "the tool server could not be reached"
# A stateless server needs no initialize; this is the newest revision both the Java and the Python
# servers speak through the handshake era.
PROTOCOL_VERSION = "2025-06-18"


# The tool server's word for a failed call (its ``_meta.error``); all the chat and the audit learn of it.
ERRORS = ("login_refused", "unreachable", "failed")
DATA_UNREADABLE = ("login_refused", "unreachable")


# Named as the Java exception: the name is the tool span's error.type.
class ToolCallFailedException(Exception):
    """A tool error the model is told of as ``Error: <message>``: a fixed sentence or the server's text;
    ``error`` is its category (anything unknown is ``failed``), ``data_source`` the one the server named."""

    def __init__(self, message: str, error=None, data_source=None):
        super().__init__(message)
        self.error = error if isinstance(error, str) and error in ERRORS else "failed"
        self.data_source = data_source if isinstance(data_source, str) else None

    @property
    def data_unreadable(self) -> bool:
        """The data source could not be read: the user should hear so, not "I don't know"."""
        return self.error in DATA_UNREADABLE


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    input_schema: str


def endpoint(url: str) -> str:
    """A bare origin means ``/mcp``."""
    return url.rstrip("/") + "/mcp" if urlsplit(url).path in ("", "/") else url


class ToolServer:
    def __init__(self, url: str, timeout: float = 20.0, transport=None):
        self.url = endpoint(url)
        self.timeout = timeout
        self._transport = transport
        self._ids = itertools.count(1)
        self._tools: list[ToolSpec] | None = None

    async def list_tools(self, bearer: str, pin: dict) -> list[ToolSpec]:
        """Asked once per pod; a failed listing is asked again by the next exchange."""
        if self._tools is None:
            result = await self._rpc("tools/list", {"_meta": pin}, bearer)
            self._tools = [ToolSpec(t["name"], t.get("description") or "", jsonw.dumps(t.get("inputSchema") or {}))
                           for t in result.get("tools") or []]
        return self._tools

    async def call_tool(self, name: str, arguments: dict, bearer: str, pin: dict) -> str:
        result = await self._rpc("tools/call", {"name": name, "arguments": arguments, "_meta": pin}, bearer)
        text = next((c.get("text", "") for c in result.get("content") or [] if c.get("type") == "text"), "")
        if result.get("isError") is True:
            # The tool server's category and data source travel in _meta; its text is for the model.
            meta = result.get("_meta") if isinstance(result.get("_meta"), dict) else {}
            raise ToolCallFailedException(text, meta.get("error"), meta.get("data_source"))
        return text

    async def _rpc(self, method: str, params: dict, bearer: str) -> dict:
        if not bearer:
            raise RuntimeError("MCP call without an authenticated user")
        request_id = next(self._ids)
        headers = {"Authorization": f"Bearer {bearer}", "Content-Type": "application/json",
                   "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": PROTOCOL_VERSION,
                   **tracing.trace_headers()}
        body = jsonw.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        try:
            # ponytail: a client per call; one shared AsyncClient if connection setup ever shows up in traces.
            async with httpx2.AsyncClient(timeout=self.timeout, transport=self._transport) as http:
                with tracing.span("http post"):
                    response = await http.post(self.url, content=body.encode("utf-8"), headers=headers)
            if response.status_code in (401, 403):
                raise ToolCallFailedException(REJECTED)
            response.raise_for_status()
            message = _message(response, request_id)
            if "result" not in message:
                raise ValueError("no result")
            return message["result"]
        except ToolCallFailedException:
            log.warning("call to the tool server failed with %s", "HttpStatus")
            raise
        except Exception as e:  # noqa: BLE001 - only the type: the exception may quote the request
            log.warning("call to the tool server failed with %s", type(e).__name__)
            raise ToolCallFailedException(UNREACHABLE) from None


def _message(response: httpx2.Response, request_id: int) -> dict:
    if response.headers.get("content-type", "").startswith("text/event-stream"):
        for line in response.text.splitlines():
            if line.startswith("data:"):
                message = json.loads(line[5:])
                if message.get("id") == request_id:
                    return message
        raise ValueError("no response in the stream")
    return json.loads(response.text)
