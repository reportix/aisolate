"""The mounted documents (Java: libs/config ``ConfigurationLoader``, ``Configuration``)."""

from __future__ import annotations

import hashlib
import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from aisolate import Stage

GROUP = "aisolate.dev"
API_VERSION = GROUP + "/v1"
PROVIDERS = ("ollama", "openai")
GLOBAL = "aisolate"
"""The one ``Global`` document's name."""
LLM_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,40}$")
LLM_ID_RULE = "1 to 41 lowercase letters, digits, _ or -, starting with a letter or digit"
_LLMS = [{"id": str, "provider": str, "url": str, "model": str, "secretRef": str}]
_DATA_SOURCES = [{"id": str, "url": str, "secretRef": str}]
_HANDLERS = {"handlers": [str]}
# Every key a document may carry (Java: its records under FAIL_ON_UNKNOWN_PROPERTIES).
_SCHEMAS: dict[str, dict[str, Any]] = {
    "Global": {"metadata": {"name": str}, "spec": {"llms": _LLMS, "dataSources": _DATA_SOURCES}},
    "Space": {"metadata": {"name": str}, "spec": {
        "displayName": str, "description": str, "access": {"users": [str]}, "llms": _LLMS,
        "dataSources": _DATA_SOURCES}},
    "Airlock": {"metadata": {"name": str}, "spec": {
        "space": str, "displayName": str, "description": str, "access": {"users": [str], "callers": [str], "clients": [str]},
        "preLLM": {"contextPreparator": _HANDLERS, "conversationMemory": _HANDLERS},
        "llm": str, "prompt": str, "tools": [str],
        "postLLM": {"answerVerifier": _HANDLERS},
        "llms": _LLMS, "dataSources": _DATA_SOURCES,
        "jar": {"digest": str, "tools": [str], "language": str}}},
}


class ConfigurationError(Exception):
    """Every problem found, one per line; the pod does not start (Java: ``ConfigurationException``)."""

    def __init__(self, problems: list[str]) -> None:
        super().__init__("\n".join(problems))
        self.problems = problems


@dataclass(frozen=True, slots=True)
class Llm:
    """One LLM: no type, it chats or embeds as far as its model can."""

    id: str
    provider: str
    url: str
    model: str
    secret_ref: str | None = None
    """The Secret holding an ``openai`` model's API key; the documents never hold the key."""


@dataclass(frozen=True, slots=True)
class DataSource:
    id: str
    url: str
    secret_ref: str | None


@dataclass(frozen=True, slots=True)
class Access:
    """Who may ask: the space's users then the airlock's own (distinct, in order), the airlock's callers,
    and the airlock's machine clients (none when absent)."""

    users: tuple[str, ...]
    callers: tuple[str, ...]
    clients: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class Code:
    """``spec.jar``: the artifact's digest, its tools, its language (``java`` when absent)."""

    digest: str
    tools: tuple[str, ...]
    language: str


@dataclass(frozen=True, slots=True)
class Airlock:
    """One served Airlock with everything resolved."""

    name: str
    """The document's name: the airlock id."""
    space: str
    description: str | None
    prompt: str
    tools: tuple[str, ...]
    """``spec.tools``: the tools the model may call."""
    handlers: Mapping[Stage, tuple[str, ...]]
    """Handler names per stage, every stage present (empty when none)."""
    llm: str
    """``spec.llm``: the id of the LLM that answers, one of ``llms``."""
    llms: tuple[Llm, ...]
    """Every LLM it sees: the Global's, its space's, its own; a nearer id replacing; by id."""
    data_sources: tuple[DataSource, ...]
    """Over the same three levels, by id."""
    access: Access
    code: Code | None

    @property
    def main_llm(self) -> Llm:
        return next(llm for llm in self.llms if llm.id == self.llm)


@dataclass(frozen=True, slots=True)
class Configuration:
    revision: str
    airlocks: Mapping[str, Airlock]
    """By name, in file order; only the airlocks with an llm and a prompt (the others run nothing)."""

    def airlock(self, name: str) -> Airlock | None:
        """The airlock ``name``, None when not served."""
        return self.airlocks.get(name)


def load(config_dir: str | Path | None, revision: str | None) -> Configuration:
    """Every ``*.yaml``/``*.yml`` directly in ``config_dir`` (name order), strict (unknown or duplicate
    keys refused), references checked, the revision computed over the raw file bytes (§2.2) and
    compared with ``revision`` when non-blank. ConfigurationError listing every problem; one
    ``AISOLATE_CONFIG_DIR is required: the directory of the release's documents`` for a blank dir."""
    if config_dir is None or not str(config_dir).strip():
        raise ConfigurationError(["AISOLATE_CONFIG_DIR is required: the directory of the release's documents"])
    files = {
        path.name: path.read_bytes()
        for path in sorted(Path(config_dir).iterdir())
        if path.suffix in (".yaml", ".yml") and path.is_file()
    }
    problems: list[str] = []
    documents: dict[str, dict[str, Any]] = {kind: {} for kind in _SCHEMAS}
    file_of: dict[str, str] = {}
    keyed: dict[str, bytes] = {}
    for file, raw in files.items():
        try:
            nodes = [n for n in yaml.load_all(raw, Loader=_StrictLoader) if isinstance(n, dict)]
        except yaml.YAMLError as unreadable:
            problems.append(f"{file}: {str(unreadable).splitlines()[0]}")
            continue
        ours = [n for n in nodes if not isinstance(n.get("apiVersion"), str) or n["apiVersion"].startswith(GROUP + "/")]
        if len(ours) > 1:
            # The revision is the hash of each document's own file, as the controller writes them.
            problems.append(f"{file}: one document per file")
            continue
        for node in ours:
            kind, name = str(node.get("kind", "")), (node.get("metadata") or {}).get("name") or ""
            where = f"{file}: {f'{kind} {name}'.strip()}"
            if node.get("apiVersion") != API_VERSION:
                problems.append(f"{where}: apiVersion {node.get('apiVersion')} is not {API_VERSION}")
            elif kind not in _SCHEMAS:
                problems.append(f"{where}: unknown kind {kind}")
            elif not isinstance(name, str) or not name.strip():
                problems.append(f"{where}: metadata.name is required")
            elif kind == "Global" and name != GLOBAL:
                problems.append(f"{where}: metadata.name must be {GLOBAL}")
            elif name in documents[kind]:
                problems.append(f"{where}: already defined in {file_of[f'{kind}/{name}']}")
            else:
                body = {k: v for k, v in node.items() if k not in ("apiVersion", "kind")}
                found = _check(body, _SCHEMAS[kind], "")
                problems += [f"{where}: {p}" for p in found]
                if not found:
                    # Kept despite a bad entry, as Java's record: what refers to it gets no second problem.
                    problems += [f"{where}: {p}" for p in _llm_problems(_get(body, "spec", "llms"), "spec.llms")]
                    documents[kind][name] = body
                    file_of[f"{kind}/{name}"] = file
                    keyed[f"{kind}/{name}"] = raw
    if not any(documents.values()) and not problems:
        problems.append(f"no documents: expected .yaml files with apiVersion {API_VERSION}")
    airlocks = _resolve(documents, file_of, problems)
    if problems:
        raise ConfigurationError(problems)
    computed = revision_of_documents(keyed)
    if revision is not None and revision.strip() and revision != computed:
        raise ConfigurationError(
            [f"revision mismatch: AISOLATE_CONFIG_REVISION says {revision}, the documents hash to {computed}"]
        )
    return Configuration(computed, airlocks)



def revision_of_documents(keyed: Mapping[str, bytes]) -> str:
    """The revision over ``{"<kind>/<name>": file bytes}``; kinds sort as strings (Airlock < Global < Space)."""
    sha = hashlib.sha256()
    for key in sorted(keyed, key=lambda k: tuple(k.split("/", 1))):
        sha.update(key.encode() + b"\0" + keyed[key])
    return sha.hexdigest()[:12]


class _StrictLoader(yaml.SafeLoader):
    """A key written twice is refused: it would otherwise load its last value, silently."""

    def construct_mapping(self, node: yaml.MappingNode, deep: bool = False) -> dict[Any, Any]:
        seen = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in seen:
                raise yaml.constructor.ConstructorError(None, None, f"duplicate key {key}", key_node.start_mark)
            seen.add(key)
        return super().construct_mapping(node, deep=deep)


_StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _StrictLoader.construct_mapping)
# No timestamps, as Java's YAML 1.2 reads: a value like 2026-10-26 is text, and Java writes it unquoted.
_StrictLoader.yaml_implicit_resolvers = {
    first: [r for r in resolvers if r[0] != "tag:yaml.org,2002:timestamp"]
    for first, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}


def _check(value: Any, schema: Any, at: str) -> list[str]:
    if value is None:
        return []
    if schema is str:
        return [] if isinstance(value, str) else [f"{at} must be a string"]
    if isinstance(schema, list):
        if not isinstance(value, list):
            return [f"{at} must be a list"]
        return [p for i, item in enumerate(value) for p in _check(item, schema[0], f"{at}[{i}]")]
    if not isinstance(value, dict):
        return [f"{at} must be an object"]
    problems = [f"{at + '.' if at else ''}{key}: unknown field" for key in value if key not in schema]
    return problems + [p for key, sub in schema.items() for p in _check(value.get(key), sub, f"{at + '.' if at else ''}{key}")]


def _get(node: Any, *path: str) -> Any:
    for key in path:
        node = node.get(key) if isinstance(node, dict) else None
    return node


def _blank(value: Any) -> bool:
    return not isinstance(value, str) or not value.strip()


def _llm_problems(entries: list[dict[str, Any]] | None, at: str) -> list[str]:
    """Each entry whole and its id once in its list; a nearer level's same id replaces it, by design."""
    problems: list[str] = []
    ids = set()
    for i, entry in enumerate(entries or ()):
        entry = entry or {}
        at_i, id = f"{at}[{i}]", entry.get("id")
        if _blank(id):
            problems.append(f"{at_i}.id is required")
        elif not LLM_ID.match(id):
            problems.append(f"{at_i}.id {id}: {LLM_ID_RULE}")
        elif id in ids:
            problems.append(f"{at_i}.id {id} is listed twice")
        ids.add(id)
        provider = entry.get("provider")
        if _blank(provider):
            problems.append(f"{at_i}.provider is required")
        elif provider not in PROVIDERS:
            problems.append(f"{at_i}.provider {provider} is not one of [{', '.join(PROVIDERS)}]")
        problems += [f"{at_i}.{key} is required" for key in ("url", "model") if _blank(entry.get(key))]
    return problems


def _client_problems(clients: list[str | None] | None) -> list[str]:
    """Java's ``Names.clientProblems``: matched exactly, so none blank, with a space, twice or ``*``."""
    named = [c for c in clients or () if not _blank(c)]
    problems = ["must not contain a blank client id"] if len(named) < len(clients or ()) else []
    problems += [f'"{c}" has a space in it' for c in dict.fromkeys(named) if re.search(r"\s", c)]
    problems += [f"lists {c} twice" for c in dict.fromkeys(named) if named.count(c) > 1]
    return problems + (["* is no wildcard: list each client id"] if "*" in named else [])


def _resolve(documents: dict[str, dict[str, Any]], file_of: dict[str, str], problems: list[str]) -> dict[str, Airlock]:
    """The references the pods must not serve without, and every airlock that runs resolved. One without an
    llm or a prompt is valid but runs nothing (a deploy brings them): left out, never refused."""
    spaces = documents["Space"]
    global_spec = _get(documents["Global"].get(GLOBAL), "spec") or {}  # none reads as empty
    airlocks = {}
    for name, airlock in documents["Airlock"].items():
        where = f"{file_of[f'Airlock/{name}']}: Airlock {name}"
        spec = airlock.get("spec") or {}
        space_id = spec.get("space")
        if not space_id:
            problems.append(f"{where}: spec.space is required")
        elif space_id not in spaces:
            problems.append(f"{where}: spec.space {space_id} is no Space")
        if not _get(spec, "access", "callers"):
            problems.append(f"{where}: spec.access.callers needs at least one caller")
        problems += [f"{where}: spec.access.clients {p}" for p in _client_problems(_get(spec, "access", "clients"))]
        llm, prompt = spec.get("llm"), spec.get("prompt")
        if _blank(llm) or _blank(prompt):
            continue
        space = spaces.get(space_id) or {}
        tools = tuple(spec.get("tools") or ())
        handlers = {
            Stage.CONTEXT_PREPARATOR: tuple(_get(spec, "preLLM", "contextPreparator", "handlers") or ()),
            Stage.CONVERSATION_MEMORY: tuple(_get(spec, "preLLM", "conversationMemory", "handlers") or ()),
            Stage.ANSWER_VERIFIER: tuple(_get(spec, "postLLM", "answerVerifier", "handlers") or ()),
        }
        jar = spec.get("jar")
        code = None
        if jar is None:
            if tools or any(handlers.values()):
                problems.append(f"{where}: lists tools or handlers but has no jar")
        else:
            if not jar.get("digest"):
                problems.append(f"{where}: spec.jar.digest is required")
            code = Code(jar.get("digest") or "", tuple(jar.get("tools") or ()), jar.get("language") or "java")
            missing = [t for t in tools if t not in code.tools]
            if missing:
                problems.append(f"{where}: spec.tools {', '.join(missing)} not in the code")
        levels = (global_spec, _get(space, "spec") or {}, spec)
        llms = {e["id"]: Llm(e["id"], e.get("provider"), e.get("url"), e.get("model"), e.get("secretRef"))
                for level in levels for e in level.get("llms") or () if e and not _blank(e.get("id"))}
        if llm not in llms:
            problems.append(f"{where}: llm {llm} is no model of it, of Space {space_id} or of AIsolate")
        data_sources = {ds.get("id") or "": DataSource(ds.get("id"), ds.get("url"), ds.get("secretRef"))
                        for level in levels for ds in level.get("dataSources") or () if ds}
        users = list(_get(space, "spec", "access", "users") or ()) + list(_get(spec, "access", "users") or ())
        access = Access(tuple(dict.fromkeys(users)), tuple(_get(spec, "access", "callers") or ()),
                        tuple(_get(spec, "access", "clients") or ()))
        airlocks[name] = Airlock(
            name, space_id, spec.get("description"), prompt, tools, handlers, llm,
            tuple(llms[k] for k in sorted(llms)), tuple(data_sources[k] for k in sorted(data_sources)), access, code,
        )
    return airlocks
