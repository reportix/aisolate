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
        "displayName": str, "description": str, "access": {"roles": [str]}, "llms": _LLMS,
        "dataSources": _DATA_SOURCES}},
    "Airlock": {"metadata": {"name": str}, "spec": {
        "space": str, "displayName": str, "description": str, "access": {"roles": [str], "callers": [str], "clients": [str]},
        "llms": _LLMS, "dataSources": _DATA_SOURCES}},
    # No dataSources: a data source needs a login, and a version holds no credential.
    "AirlockVersion": {"metadata": {"name": str}, "spec": {
        "airlock": str, "version": str, "description": str,
        "preLLM": {"contextPreparator": _HANDLERS, "conversationMemory": _HANDLERS},
        "llm": str, "prompt": str, "tools": [str],
        "postLLM": {"answerVerifier": _HANDLERS},
        "llms": _LLMS,
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
    """Who may ask: the space's roles then the airlock's own (distinct, in order), the airlock's callers,
    and the airlock's machine clients (none when absent)."""

    roles: tuple[str, ...]
    callers: tuple[str, ...]
    clients: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class Code:
    """``spec.jar``: the artifact's digest, its tools, its language (``java`` when absent)."""

    digest: str
    tools: tuple[str, ...]
    language: str


@dataclass(frozen=True, slots=True)
class Version:
    """One served AirlockVersion with everything resolved."""

    name: str
    """``<airlock>-<version>``, the document's name."""
    space: str
    airlock: str
    label: str
    """``spec.version``, for example ``v1``: what ``_meta.version`` and the chat path carry."""
    description: str | None
    prompt: str
    tools: tuple[str, ...]
    """``spec.tools``: the tools the model may call."""
    handlers: Mapping[Stage, tuple[str, ...]]
    """Handler names per stage, every stage present (empty when none)."""
    llm: str
    """``spec.llm``: the id of the LLM that answers, one of ``llms``."""
    llms: tuple[Llm, ...]
    """Every LLM it sees: the Global's, its space's, its airlock's, its own; a nearer id replacing; by id."""
    data_sources: tuple[DataSource, ...]
    """Its airlock's: the Global's, its space's, the airlock's own, by id; a version defines none."""
    access: Access
    """Its airlock's."""
    code: Code | None

    @property
    def main_llm(self) -> Llm:
        return next(llm for llm in self.llms if llm.id == self.llm)


@dataclass(frozen=True, slots=True)
class Configuration:
    revision: str
    versions: Mapping[str, Version]
    """By document name, in file order."""

    def version(self, airlock: str, label: str) -> Version | None:
        """The version ``label`` of ``airlock``, None when not served."""
        return next((v for v in self.versions.values() if v.airlock == airlock and v.label == label), None)


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
    versions = _resolve(documents, file_of, problems)
    if problems:
        raise ConfigurationError(problems)
    computed = revision_of_documents(keyed)
    if revision is not None and revision.strip() and revision != computed:
        raise ConfigurationError(
            [f"revision mismatch: AISOLATE_CONFIG_REVISION says {revision}, the documents hash to {computed}"]
        )
    return Configuration(computed, versions)


def revision_of_documents(keyed: Mapping[str, bytes]) -> str:
    """The revision over ``{"<kind>/<name>": file bytes}``; kinds sort as strings, as Java's
    ``Comparator.comparing(kind)`` does (Airlock < AirlockVersion < Global < Space)."""
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
# No timestamps, as Java's YAML 1.2 reads: a version named 2026-10-26 is text, and Java writes it unquoted.
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


def _role_problems(roles: list[str | None] | None) -> list[str]:
    """Java's ``Names.roleProblems``: matched exactly, so none blank, with a space, twice or ``*``."""
    named = [r for r in roles or () if not _blank(r)]
    problems = ["must not contain a blank role"] if len(named) < len(roles or ()) else []
    problems += [f'"{r}" has a space in it' for r in dict.fromkeys(named) if re.search(r"\s", r)]
    problems += [f"lists {r} twice" for r in dict.fromkeys(named) if named.count(r) > 1]
    return problems + (["* is no role: list each role; only callers take * for any"] if "*" in named else [])


def _resolve(documents: dict[str, dict[str, Any]], file_of: dict[str, str], problems: list[str]) -> dict[str, Version]:
    """The references the pods must not serve without, and every version resolved."""
    spaces, airlocks = documents["Space"], documents["Airlock"]
    global_spec = _get(documents["Global"].get(GLOBAL), "spec") or {}  # none reads as empty
    for name, space in spaces.items():
        problems += [f"{file_of[f'Space/{name}']}: Space {name}: spec.access.roles {p}"
                     for p in _role_problems(_get(space, "spec", "access", "roles"))]
    for name, airlock in airlocks.items():
        where = f"{file_of[f'Airlock/{name}']}: Airlock {name}"
        spec = airlock.get("spec") or {}
        space_id = spec.get("space")
        if not space_id:
            problems.append(f"{where}: spec.space is required")
        elif space_id not in spaces:
            problems.append(f"{where}: spec.space {space_id} is no Space")
        if not _get(spec, "access", "callers"):
            problems.append(f"{where}: spec.access.callers needs at least one caller")
        problems += [f"{where}: spec.access.roles {p}" for p in _role_problems(_get(spec, "access", "roles"))]
        problems += [f"{where}: spec.access.clients {p}" for p in _client_problems(_get(spec, "access", "clients"))]
    versions = {}
    for name, version in documents["AirlockVersion"].items():
        where = f"{file_of[f'AirlockVersion/{name}']}: AirlockVersion {name}"
        spec = version.get("spec") or {}
        airlock_id, label = spec.get("airlock"), spec.get("version")
        airlock = airlocks.get(airlock_id)
        if airlock is None:
            problems.append(f"{where}: spec.airlock is required" if _blank(airlock_id)
                            else f"{where}: spec.airlock {airlock_id} is no Airlock")
            continue
        if _blank(label) or name != f"{airlock_id}-{label}":
            problems.append(f"{where}: metadata.name must be {airlock_id}-{label}")
        airlock_spec = airlock.get("spec") or {}
        space_id = airlock_spec.get("space")
        space = spaces.get(space_id) or {}
        prompt = spec.get("prompt")
        if _blank(prompt):
            problems.append(f"{where}: spec.prompt is required")
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
        levels = (global_spec, _get(space, "spec") or {}, airlock_spec, spec)
        llms = {e["id"]: Llm(e["id"], e.get("provider"), e.get("url"), e.get("model"), e.get("secretRef"))
                for level in levels for e in level.get("llms") or () if e and not _blank(e.get("id"))}
        llm = spec.get("llm")
        if _blank(llm):
            problems.append(f"{where}: spec.llm is required")
        elif llm not in llms:
            problems.append(f"{where}: llm {llm} is no model of it, of Airlock {airlock_id}, of Space {space_id} "
                            "or of AIsolate")
        data_sources = {ds.get("id") or "": DataSource(ds.get("id"), ds.get("url"), ds.get("secretRef"))
                        for level in levels[:3] for ds in level.get("dataSources") or () if ds}
        roles = list(_get(space, "spec", "access", "roles") or ()) + list(_get(airlock_spec, "access", "roles") or ())
        access = Access(tuple(dict.fromkeys(roles)), tuple(_get(airlock_spec, "access", "callers") or ()),
                        tuple(_get(airlock_spec, "access", "clients") or ()))
        versions[name] = Version(
            name, space_id, airlock_id, label, spec.get("description"), prompt or "", tools, handlers, llm or "",
            tuple(llms[k] for k in sorted(llms)), tuple(data_sources[k] for k in sorted(data_sources)), access, code,
        )
    return versions
