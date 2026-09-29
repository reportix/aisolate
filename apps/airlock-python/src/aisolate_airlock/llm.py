"""The models: Ollama's ``/api/chat`` (non-streaming, tools, ``format``) and ``/api/embed``, with the options
the Java airlock gives Spring AI's Ollama client, or an OpenAI-compatible server's ``/chat/completions`` and
``/embeddings``; one client per catalog entry. No retries: a model that fails is an error event."""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass, field

import httpx2
from aisolate import LlmError
from prometheus_client import Counter

from aisolate_airlock import tracing

TOKENS = Counter("gen_ai_client_token_usage", "Measures number of input and output tokens used",
                 ["airlock", "gen_ai_operation_name", "gen_ai_request_model", "gen_ai_system", "gen_ai_token_type"])


def _count(airlock: str, operation: str, model: str, system: str,
           counts: tuple[tuple[str, int | None], ...]) -> None:
    """Spring AI's ``ModelUsageMetricsGenerator``: one increment per token type the provider reported."""
    for kind, n in counts:
        if n is not None:
            TOKENS.labels(airlock=airlock, gen_ai_token_type=kind,
                          gen_ai_operation_name=operation, gen_ai_system=system, gen_ai_request_model=model).inc(n)


class ModelUnavailable(Exception):
    """The model server refused, timed out or could not be reached; decided by type, never message."""


@dataclass
class Reply:
    text: str
    tool_calls: list[dict] = field(default_factory=list)
    input_tokens: int | None = None
    output_tokens: int | None = None


PROVIDERS = ("ollama", "openai")


def _functions(tools: list[dict]) -> list[dict]:
    return [{"type": "function", "function": {"name": t["name"], "description": t["description"],
                                              "parameters": json.loads(t["input_schema"])}} for t in tools]


class _Model:
    """One model of the catalog: ``{id, provider, url, model}``, no type; it chats or embeds as far as its model can."""

    provider: str

    def __init__(self, id: str, url: str, model: str, read_timeout: float = 90.0, transport=None,
                 sync_transport=None):
        self.id = id
        self.url = url.rstrip("/")
        self.model = model
        self.timeout = httpx2.Timeout(read_timeout, connect=5.0)
        self._transport = transport
        self._sync_transport = sync_transport

    def _span(self) -> dict:
        return {"gen_ai.operation.name": "chat", "gen_ai.system": self.provider, "gen_ai.request.model": self.model,
                "aisolate.llm": self.id}

    def _cannot_chat(self) -> LlmError:
        return LlmError(f"{self.model} (model {self.id}) cannot chat: "
                        "name a chat model such as granite in aisolate.llm(...)")

    def _cannot_embed(self) -> LlmError:
        return LlmError(f"{self.model} (model {self.id}) cannot embed: "
                        "name an embedding model such as nomic in aisolate.llm(...)")


class Ollama(_Model):
    provider = "ollama"

    def _chat_body(self, messages: list[dict]) -> dict:
        return {"model": self.model, "messages": messages, "stream": False,
                "options": {"temperature": 0.1}, "keep_alive": "-1m", "think": False}

    def _used(self, span, airlock: str, answer: dict) -> tuple[int | None, int | None]:
        input_tokens, output_tokens = answer.get("prompt_eval_count"), answer.get("eval_count")
        tracing.tag(span, "gen_ai.usage.input_tokens", input_tokens)
        tracing.tag(span, "gen_ai.usage.output_tokens", output_tokens)
        if input_tokens is not None or output_tokens is not None:
            i, o = input_tokens or 0, output_tokens or 0
            _count(airlock, "chat", self.model, self.provider, (("input", i), ("output", o), ("total", i + o)))
        return input_tokens, output_tokens

    async def chat(self, messages: list[dict], tools: list[dict] | None = None, schema: str | None = None,
                   airlock: str = "none") -> Reply:
        """The agent loop's turn; any failure is ModelUnavailable."""
        body = self._chat_body(messages)
        if tools:
            body["tools"] = _functions(tools)
        if schema:
            body["format"] = json.loads(schema)
        with tracing.span(f"chat {self.model}", self._span()) as span:
            try:
                async with httpx2.AsyncClient(timeout=self.timeout, transport=self._transport) as http:
                    with tracing.span("http post"):
                        response = await http.post(f"{self.url}/api/chat", json=body)
                response.raise_for_status()
                answer = response.json()
                message = answer["message"]
            except (httpx2.HTTPError, ValueError, KeyError, TypeError) as e:
                raise ModelUnavailable(type(e).__name__) from None
            reply = Reply(message.get("content") or "", message.get("tool_calls") or [])
            reply.input_tokens, reply.output_tokens = self._used(span, airlock, answer)
            return reply

    def ask(self, prompt: str, airlock: str = "none") -> str:
        """A handler's ``llm(id).chat``: synchronous, handlers run on worker threads."""
        with tracing.span(f"chat {self.model}", self._span()) as span:
            with httpx2.Client(timeout=self.timeout, transport=self._sync_transport) as http, tracing.span("http post"):
                response = http.post(f"{self.url}/api/chat",
                                     json=self._chat_body([{"role": "user", "content": prompt}]))
            if response.status_code == 400 and "does not support chat" in response.text:
                raise self._cannot_chat()
            response.raise_for_status()
            answer = response.json()
            self._used(span, airlock, answer)
            return answer["message"]["content"]

    def embed(self, input: str | list[str], airlock: str = "none"):
        """A vector for a text, one per text for a list; synchronous, handlers run on worker threads."""
        with tracing.span("embedding", {"gen_ai.request.model": self.model, "aisolate.llm": self.id}) as span:
            with httpx2.Client(timeout=self.timeout, transport=self._sync_transport) as http, tracing.span("http post"):
                response = http.post(f"{self.url}/api/embed", json={"model": self.model, "input": input})
            if response.status_code == 501:  # Ollama's answer for a model that cannot embed
                raise self._cannot_embed()
            response.raise_for_status()
            answer = response.json()
            tracing.tag(span, "gen_ai.usage.input_tokens", answer.get("prompt_eval_count"))
            # Spring AI's Ollama embedding reports DefaultUsage(prompt_eval_count or 0, 0).
            used = answer.get("prompt_eval_count") or 0
            _count(airlock, "embedding", self.model, self.provider, (("input", used), ("output", 0), ("total", used)))
            vectors = [[float(x) for x in vector] for vector in answer["embeddings"]]
            return vectors[0] if isinstance(input, str) else vectors


def _arguments_text(arguments) -> str:
    return arguments if isinstance(arguments, str) else json.dumps({} if arguments is None else arguments)


def _openai_messages(messages: list[dict]) -> list[dict]:
    """The exchange's conversation, Ollama-shaped, as OpenAI's: each tool result answers its call by id, in the
    order the assistant asked them."""
    out: list[dict] = []
    pending: list[str] = []
    for m in messages:
        if m.get("tool_calls"):
            calls = [{"id": c.get("id") or f"call_{len(out)}_{i}", "type": "function",
                      "function": {"name": (c.get("function") or {}).get("name") or "",
                                   "arguments": _arguments_text((c.get("function") or {}).get("arguments"))}}
                     for i, c in enumerate(m["tool_calls"])]
            pending = [c["id"] for c in calls]
            out.append({"role": "assistant", "content": m.get("content") or "", "tool_calls": calls})
        elif m.get("role") == "tool":
            out.append({"role": "tool", "tool_call_id": pending.pop(0) if pending else "", "content": m["content"]})
        else:
            out.append({"role": m["role"], "content": m["content"]})
    return out


class OpenAi(_Model):
    """An OpenAI-compatible server (OpenAI, Gemini's OpenAI endpoint, Ollama's ``/v1``): ``url`` is its base URL
    with the version, ``/v1``; its API key, when it has one, the bearer of every request."""

    provider = "openai"

    def __init__(self, id: str, url: str, model: str, read_timeout: float = 90.0, transport=None,
                 sync_transport=None, api_key: str | None = None):
        super().__init__(id, url, model, read_timeout, transport, sync_transport)
        self._headers = {"Authorization": f"Bearer {api_key}"} if (api_key or "").strip() else {}

    def _body(self, messages: list[dict]) -> dict:
        return {"model": self.model, "messages": messages, "temperature": 0.1}

    def _key_refused(self, response) -> None:
        # The body may quote the key, masked or not: neither reaches the message.
        if response.status_code == 401:
            raise LlmError(f"{self.model} (model {self.id}): the API key was refused; "
                           "set it again on the model in AIsolate")

    def _used(self, span, airlock: str, answer: dict) -> tuple[int | None, int | None]:
        usage = answer.get("usage") or {}
        input_tokens, output_tokens = usage.get("prompt_tokens"), usage.get("completion_tokens")
        tracing.tag(span, "gen_ai.usage.input_tokens", input_tokens)
        tracing.tag(span, "gen_ai.usage.output_tokens", output_tokens)
        if input_tokens is not None or output_tokens is not None:
            i, o = input_tokens or 0, output_tokens or 0
            total = usage.get("total_tokens")
            _count(airlock, "chat", self.model, self.provider,
                   (("input", i), ("output", o), ("total", i + o if total is None else total)))
        return input_tokens, output_tokens

    async def chat(self, messages: list[dict], tools: list[dict] | None = None, schema: str | None = None,
                   airlock: str = "none") -> Reply:
        """The agent loop's turn; any failure is ModelUnavailable."""
        body = self._body(_openai_messages(messages))
        if tools:
            body["tools"] = _functions(tools)
        if schema:
            # Not strict: OpenAI's strict mode refuses a schema with optional properties, as the answer format's.
            body["response_format"] = {"type": "json_schema", "json_schema": {
                "name": "json_schema", "strict": False, "schema": json.loads(schema)}}
        with tracing.span(f"chat {self.model}", self._span()) as span:
            try:
                async with httpx2.AsyncClient(timeout=self.timeout, transport=self._transport,
                                              headers=self._headers) as http:
                    with tracing.span("http post"):
                        response = await http.post(f"{self.url}/chat/completions", json=body)
                response.raise_for_status()
                answer = response.json()
                message = answer["choices"][0]["message"]
                calls = [{"id": c["id"], "function": {"name": c["function"]["name"],
                                                      "arguments": c["function"].get("arguments")}}
                         for c in message.get("tool_calls") or ()]
            except (httpx2.HTTPError, ValueError, KeyError, TypeError, IndexError) as e:
                raise ModelUnavailable(type(e).__name__) from None
            reply = Reply(message.get("content") or "", calls)
            reply.input_tokens, reply.output_tokens = self._used(span, airlock, answer)
            return reply

    def ask(self, prompt: str, airlock: str = "none") -> str:
        """A handler's ``llm(id).chat``: synchronous, handlers run on worker threads."""
        with tracing.span(f"chat {self.model}", self._span()) as span:
            with httpx2.Client(timeout=self.timeout, transport=self._sync_transport, headers=self._headers) as http, \
                    tracing.span("http post"):
                response = http.post(f"{self.url}/chat/completions",
                                     json=self._body([{"role": "user", "content": prompt}]))
            if response.status_code == 400 and "does not support chat" in response.text:
                raise self._cannot_chat()
            self._key_refused(response)
            response.raise_for_status()
            answer = response.json()
            self._used(span, airlock, answer)
            return answer["choices"][0]["message"]["content"]

    def embed(self, input: str | list[str], airlock: str = "none"):
        """A vector for a text, one per text for a list, in the order of their index; synchronous."""
        with tracing.span("embedding", {"gen_ai.request.model": self.model, "aisolate.llm": self.id}) as span:
            with httpx2.Client(timeout=self.timeout, transport=self._sync_transport, headers=self._headers) as http, \
                    tracing.span("http post"):
                response = http.post(f"{self.url}/embeddings", json={"model": self.model, "input": input})
            if response.status_code == 501:  # Ollama's /v1 passes on /api/embed's answer
                raise self._cannot_embed()
            self._key_refused(response)
            response.raise_for_status()
            answer = response.json()
            used = (answer.get("usage") or {}).get("prompt_tokens")
            tracing.tag(span, "gen_ai.usage.input_tokens", used)
            _count(airlock, "embedding", self.model, self.provider,
                   (("input", used or 0), ("output", 0), ("total", used or 0)))
            vectors = [[float(x) for x in d["embedding"]] for d in sorted(answer["data"], key=lambda d: d["index"])]
            return vectors[0] if isinstance(input, str) else vectors


def catalog(env: Mapping[str, str], read_timeout: float = 90.0, transport=None,
            sync_transport=None) -> tuple[str, dict[str, Ollama | OpenAi]]:
    """The main model's id and every model by id, from ``AISOLATE_LLM`` and ``AISOLATE_LLM_<i>_*`` (0 until the
    first missing ``_ID``; ``_API_KEY`` optional); ValueError, refusing the start, for what the pod cannot serve. No
    defaults."""
    llms: dict[str, Ollama | OpenAi] = {}
    i = 0
    while f"AISOLATE_LLM_{i}_ID" in env:
        prefix = f"AISOLATE_LLM_{i}_"
        for key in ("ID", "URL", "MODEL"):
            if not (env.get(prefix + key) or "").strip():
                raise ValueError(f"{prefix}{key} is blank")
        id, provider = env[prefix + "ID"], env.get(prefix + "PROVIDER") or ""
        if id in llms:
            raise ValueError(f"model {id} is defined twice")
        if provider not in PROVIDERS:
            raise ValueError(f"model {id}: provider {provider} is not one of [{', '.join(PROVIDERS)}]")
        args = (id, env[prefix + "URL"], env[prefix + "MODEL"], read_timeout, transport, sync_transport)
        llms[id] = OpenAi(*args, api_key=env.get(prefix + "API_KEY")) if provider == "openai" else Ollama(*args)
        i += 1
    main = env.get("AISOLATE_LLM") or ""
    if not main.strip():
        raise ValueError("AISOLATE_LLM is blank")
    if main not in llms:
        raise ValueError(f"AISOLATE_LLM {main} is none of the AISOLATE_LLM_<i>_ID")
    return main, llms
