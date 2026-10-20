"""The build: discover a project's tools and handlers by importing its package locally, write
``aisolate-manifest.json`` and zip the artifact (docs/python-artifact.md). The runtimes call
:func:`discover` and :func:`check` on the same package at start, so the build and the pods agree on
what a project contains.

Command line: ``python -m aisolate build [PROJECT_DIR] [--arch x86_64|aarch64] [--out FILE]``; prints
the artifact's path and its ``sha256:<hex>``; exit 1 with one problem per line on stderr.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import inspect
import io
import json
import os
import pkgutil
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
import zipfile
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Final

from aisolate.handlers import Stage, handler_mark
from aisolate.tools import Entity, Tool, ToolPlugin, tool_marks

MANIFEST: Final = "aisolate-manifest.json"
MANIFEST_SCHEMA: Final = 1

_COLUMN_NAME = re.compile(r"[a-z_][a-z0-9_]*")
_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
CONSTRAINTS: Final = Path(__file__).with_name("runtime-constraints.txt")
_ARCHES: Final = {"x86_64": "x86_64", "amd64": "x86_64", "aarch64": "aarch64", "arm64": "aarch64"}
_NO_WHEEL = re.compile(r"(?:all versions of |\b)([A-Za-z0-9][\w.-]*?)(?:==\S+)? ha(?:s|ve) no (?:usable )?wheels")


@dataclass(frozen=True, slots=True)
class Handler:
    name: str
    stage: Stage
    order: int
    description: str | None
    instance: object
    data_sources: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class Catalog:
    """What a package contains: its plugins (constructed), their tools and entities, its handlers."""

    package: str
    plugins: Sequence[ToolPlugin]
    handlers: Sequence[Handler]

    def tools(self) -> Mapping[str, tuple[ToolPlugin, Tool]]:
        """Every tool by name with the plugin that publishes it."""
        return {tool.name: (plugin, tool) for plugin in self.plugins for tool in plugin.tools()}

    def entities(self) -> Mapping[str, tuple[ToolPlugin, Entity]]:
        """Every entity by name with the plugin that declares it."""
        return {entity.name: (plugin, entity) for plugin in self.plugins for entity in plugin.entities}


def discover(package: str) -> Catalog:
    """Import ``package`` and every submodule (``pkgutil.walk_packages``), then collect every concrete
    :class:`ToolPlugin` subclass and every decorated handler class defined in it (``__module__``
    under ``package``; imported names from elsewhere are skipped). Each is constructed once.

    Raises ValueError naming every problem: two tools or two entities with one name, two handlers
    with one name, a plugin with entities but no ``data_source``, a tool handler that is no method.
    """
    root = importlib.import_module(package)
    modules = [root]
    if hasattr(root, "__path__"):
        for info in sorted(pkgutil.walk_packages(root.__path__, package + "."), key=lambda i: i.name):
            modules.append(importlib.import_module(info.name))
    problems: list[str] = []
    plugins: list[ToolPlugin] = []
    handlers: list[Handler] = []
    for module in modules:
        for cls in vars(module).values():
            # Defined in this very module: a class imported elsewhere is counted where it lives.
            if not isinstance(cls, type) or cls.__module__ != module.__name__ or inspect.isabstract(cls):
                continue
            mark = handler_mark(cls)
            is_plugin = issubclass(cls, ToolPlugin) and cls is not ToolPlugin
            if is_plugin and mark is not None:
                problems.append(f"{_qualified(cls)} is both a tool plugin and a handler")
            elif is_plugin:
                for attribute, (value, _) in tool_marks(cls).items():
                    if not inspect.isfunction(value):
                        problems.append(f"{_qualified(cls)}.{attribute}: a @tool must be a plain method")
                if (plugin := _create(cls, problems)) is not None:
                    plugins.append(plugin)
            elif mark is not None and (instance := _create(cls, problems)) is not None:
                handlers.append(
                    Handler(cls.__name__, mark.stage, mark.order, mark.description, instance, mark.data_sources)
                )
    problems += catalog_problems(plugins)
    seen: set[str] = set()
    for handler in handlers:
        if handler.name in seen:
            problems.append(f"handler {handler.name} is declared twice")
        seen.add(handler.name)
    if problems:
        raise ValueError("\n".join(problems))
    return Catalog(package, tuple(plugins), tuple(handlers))


def catalog_problems(plugins: Sequence[ToolPlugin]) -> list[str]:
    """``CatalogCheck.check`` without the rule sets (the tool server checks those), every problem in
    Java's words."""
    problems: list[str] = []
    tools: set[str] = set()
    entities: set[str] = set()
    for plugin in plugins:
        name = getattr(plugin, "name", None)
        if _blank(name):
            problems.append("a plugin has no name")
        plugin_tools = plugin.tools()
        problems += [f"a tool of plugin {name} has no name" for tool in plugin_tools if _blank(tool.name)]
        problems += [f"a entity of plugin {name} has no name" for e in plugin.entities if _blank(e.name)]
        if plugin.entities and _blank(plugin.data_source):
            problems.append(f"plugin {name} declares entities but names no data source")
        for tool in plugin_tools:
            if tool.name in tools:
                problems.append(f"tool {tool.name} is published twice")
            tools.add(tool.name)
        for entity in plugin.entities:
            if entity.name in entities:
                problems.append(f"entity {entity.name} is declared twice")
            entities.add(entity.name)
            if entity.row_key not in entity.columns:
                problems.append(f"entity {entity.name}: row key {entity.row_key} is not a column")
            if entity.subject not in entity.columns:
                problems.append(f"entity {entity.name}: subject {entity.subject} is not a column")
            problems += [
                f"entity {entity.name}: column {column} must be lowercase letters, digits and _, "
                "starting with a letter or _"
                for column in entity.columns
                if not _COLUMN_NAME.fullmatch(column)
            ]
    return problems


def manifest(
    catalog: Catalog, rules: Sequence[str], vendored: Sequence[Mapping[str, str]], arch: str | None = None
) -> dict[str, Any]:
    """The manifest JSON (``docs/python-artifact.schema.json``) of ``catalog``: entries sorted by name,
    so the same code gives the same bytes. ``arch`` as :func:`linux_arch` chooses it."""
    return _manifest_of(catalog, rules, vendored, arch)


def _manifest_of(
    catalog: Catalog, rules: Sequence[str], vendored: Sequence[Mapping[str, str]], arch: str | None = None
) -> dict[str, Any]:
    from aisolate import __version__

    def by_name(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
        return sorted(items, key=lambda item: item["name"])

    plugins, tools, entities = [], [], []
    for plugin in catalog.plugins:
        plugin_tools = plugin.tools()
        plugins.append(
            {
                "name": plugin.name,
                "class": _qualified(type(plugin)),
                "data_source": plugin.data_source,
                "identity_attribute": plugin.identity_attribute,
                "entities": sorted(e.name for e in plugin.entities),
                "tools": sorted(t.name for t in plugin_tools),
            }
        )
        tools += [
            {
                "name": t.name,
                "description": t.description,
                "input_schema": t.input_schema,
                "plugin": plugin.name,
                "data_source": plugin.data_source,
            }
            for t in plugin_tools
        ]
        entities += [entity_json(e, plugin) for e in plugin.entities]
    handlers = [
        {
            "name": h.name,
            "class": _qualified(type(h.instance)),
            "stage": str(h.stage),
            "order": h.order,
            "description": h.description,
            # Only when named, so a manifest built before handlers named any still matches its code.
            **({"data_sources": list(h.data_sources)} if h.data_sources else {}),
        }
        for h in catalog.handlers
    ]
    document = {
        "schema": MANIFEST_SCHEMA,
        "language": "python",
        "sdk": __version__,
        "python": f"{sys.version_info.major}.{sys.version_info.minor}",
        # Always, binary wheels or not: the pod checks it before importing anything vendored.
        "arch": linux_arch(arch),
        "package": catalog.package,
        "plugins": by_name(plugins),
        "tools": by_name(tools),
        "entities": by_name(entities),
        "handlers": by_name(handlers),
        "rules": sorted(rules),
        "vendored": sorted(({"name": v["name"], "version": v["version"]} for v in vendored), key=lambda v: v["name"]),
    }
    # Tuples and mappings become what the manifest file holds, so check() compares like with like.
    return json.loads(json.dumps(document))


def entity_json(entity: Entity, plugin: ToolPlugin) -> dict[str, Any]:
    """An entity as the manifest and the conformance fixtures carry it."""
    return {
        "name": entity.name,
        "plugin": plugin.name,
        "data_source": plugin.data_source,
        "from": entity.from_,
        "row_key": entity.row_key,
        "subject": entity.subject,
        "subject_name": entity.subject_name,
        "period": entity.period,
        "columns": [
            {
                "name": c.name,
                "expression": c.expression,
                "type": str(c.type),
                "unit": c.unit,
                "unit_column": c.unit_column,
                "decimals": c.decimals,
                # Only when set, so a manifest built before facts existed still matches its code.
                **({"fact": True} if c.fact else {}),
            }
            for c in entity.columns.values()
        ],
    }


def check(catalog: Catalog, manifest: Mapping[str, Any]) -> list[str]:
    """What differs between the code a pod imported and the manifest the backend read: a tool or
    handler missing on either side, a tool's description or input schema, an entity's definition, a
    handler's stage or order. Empty when they match; a runtime refuses to start otherwise."""
    problems: list[str] = []
    if manifest.get("package") != catalog.package:
        problems.append(f"the manifest is of package {manifest.get('package')}, the code is {catalog.package}")
    code = _manifest_of(catalog, manifest.get("rules", []), manifest.get("vendored", []), manifest.get("arch"))
    for section, word in (("plugins", "plugin"), ("tools", "tool"), ("entities", "entity"), ("handlers", "handler")):
        mine = {item["name"]: item for item in code[section]}
        theirs = {item.get("name"): item for item in manifest.get(section, [])}
        for name in sorted(theirs.keys() - mine.keys(), key=str):
            problems.append(f"{word} {name} is in the manifest but not in the code")
        for name in sorted(mine.keys() - theirs.keys()):
            problems.append(f"{word} {name} is in the code but not in the manifest")
        for name in sorted(mine.keys() & theirs.keys(), key=str):
            fields = [key for key in mine[name] if mine[name][key] != theirs[name].get(key)]
            if fields:
                problems.append(f"{word} {name} differs from the manifest: {', '.join(fields)}")
    return problems


def build(project_dir: Path, out: Path | None = None, arch: str | None = None) -> Path:
    """Vendor the project's own dependencies for Linux on ``arch`` (:func:`vendor`), discover the
    single package under ``project_dir/src``, write the manifest, and zip ``aisolate-manifest.json``,
    ``src/<package>/**`` (no ``__pycache__``, no ``*.pyc``), ``rules/*.json`` and ``vendor/**``, entries
    sorted, timestamps fixed to 1980-01-01 so the digest is reproducible.

    ``out`` defaults to ``project_dir/build/aisolate/<package>.zip``. Raises ValueError with every problem.
    """
    project_dir = Path(project_dir).resolve()
    src = project_dir / "src"
    packages = sorted(p.name for p in src.iterdir() if (p / "__init__.py").is_file()) if src.is_dir() else []
    if len(packages) != 1:
        raise ValueError(f"src/ must hold exactly one package, found: {', '.join(packages) or 'none'}")
    package = packages[0]
    if str(src) not in sys.path:
        sys.path.insert(0, str(src))
    with tempfile.TemporaryDirectory() as scratch:
        vendor_dir = Path(scratch)
        vendor(project_dir, vendor_dir, arch)
        # Last, as in the pods; here what this machine installed for the project wins over Linux wheels.
        sys.path.append(scratch)
        try:
            catalog = discover(package)
        finally:
            sys.path.remove(scratch)
        entries = _files(vendor_dir, "vendor")
        vendored = [_distribution(m) for m in sorted(vendor_dir.glob("*.dist-info/METADATA"))]
    entries |= _files(src / package, f"src/{package}")
    rules_dir = project_dir / "rules"
    rule_files = sorted(rules_dir.glob("*.json")) if rules_dir.is_dir() else []
    entries |= {f"rules/{f.name}": _read(f) for f in rule_files}
    document = manifest(catalog, [f"rules/{f.name}" for f in rule_files], vendored, arch)
    entries[MANIFEST] = (json.dumps(document, indent=2, ensure_ascii=False) + "\n").encode()

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, entries[name])
    target = Path(out) if out is not None else project_dir / "build" / "aisolate" / f"{package}.zip"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(buffer.getvalue())
    return target


def vendor(project_dir: Path, target: Path, arch: str | None = None) -> None:
    """Install into ``target`` what ``project_dir/pyproject.toml`` depends on besides the SDK and the
    runtime's pinned set (:data:`CONSTRAINTS`, which the pods load first): Linux binary wheels for
    Python 3.13 on :func:`linux_arch`, resolved with uv against the pinned set.

    Raises ValueError naming a package the pinned set holds at a version the project cannot use, or one
    with no such wheel. Without a dependency nothing is installed, so no uv is needed.
    """
    requirements = _dependencies(project_dir)
    if not requirements:
        return
    uv = os.environ.get("UV") or shutil.which("uv")
    if uv is None:
        raise ValueError(f"install uv to vendor dependencies ({', '.join(requirements)}): https://docs.astral.sh/uv/")
    arch = linux_arch(arch)
    pins = [line for line in CONSTRAINTS.read_text().splitlines() if line.strip() and not line.startswith("#")]
    pinned = dict(map(_pin, pins))
    linux = ["--python", sys.executable, "--python-version", "3.13", "--python-platform", f"{arch}-manylinux_2_28",
             "--only-binary", ":all:"]
    try:
        resolved = _resolve(uv, requirements, pins, linux)
    except subprocess.CalledProcessError as failure:
        raise ValueError(_unresolvable(uv, requirements, pins, linux, failure.stderr, arch)) from None
    own = [f"{name}=={version}" for name, version in sorted(resolved.items()) if name not in pinned]
    if own:
        install = [uv, "pip", "install", "--target", str(target), "--no-deps", "--quiet", *linux, "-r", "-"]
        done = subprocess.run(install, input="\n".join(own), capture_output=True, text=True)
        if done.returncode:
            raise ValueError(f"uv could not install {', '.join(own)}: {' '.join(done.stderr.split())}")
    # Of this machine, not the project: uv's lock and cache notes, console scripts with a local shebang,
    # the RECORDs hashing them. What is left is the same on every machine, so is the digest.
    shutil.rmtree(target / "bin", ignore_errors=True)
    for leftover in [target / ".lock", *target.glob("*.dist-info/RECORD"), *target.glob("*.dist-info/uv_cache.json")]:
        leftover.unlink(missing_ok=True)


def linux_arch(arch: str | None = None) -> str:
    """The CPU the artifact is vendored for, ``x86_64`` or ``aarch64`` (``amd64``, ``arm64`` alike):
    ``arch``, else ``AISOLATE_ARCH``, else this machine's, as the local cluster runs on it."""
    chosen = arch or os.environ.get("AISOLATE_ARCH") or platform.machine()
    if chosen.lower() not in _ARCHES:
        raise ValueError(f"cannot vendor for {chosen}: set --arch or AISOLATE_ARCH to x86_64 or aarch64")
    return _ARCHES[chosen.lower()]


def digest(path: Path) -> str:
    """``sha256:<hex>`` of a file's bytes, the form a version pins its code by."""
    return "sha256:" + hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main(argv: Sequence[str] | None = None) -> int:
    """``python -m aisolate build`` / ``aisolate-build``."""
    parser = argparse.ArgumentParser(prog="python -m aisolate build", description="Build the project's artifact.")
    parser.add_argument("project_dir", nargs="?", default=".", type=Path)
    parser.add_argument("--arch", help="the cluster's CPU, x86_64 or aarch64 (default AISOLATE_ARCH, else this one's)")
    parser.add_argument("--out", type=Path, help="the zip to write")
    args = parser.parse_args(argv)
    try:
        artifact = build(args.project_dir, args.out, args.arch)
    except Exception as problem:  # every problem goes to the developer, one per line
        print(str(problem) or type(problem).__name__, file=sys.stderr)
        return 1
    print(artifact)
    print(digest(artifact))
    return 0


def _qualified(cls: type) -> str:
    return f"{cls.__module__}.{cls.__qualname__}"


def _blank(value: object) -> bool:
    return not isinstance(value, str) or not value.strip()


def _create(cls: type, problems: list[str]) -> Any:
    try:
        return cls()
    except Exception as failure:
        problems.append(f"{_qualified(cls)} could not be created: {failure or type(failure).__name__}")
        return None


def _files(root: Path, prefix: str) -> dict[str, bytes]:
    found = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if "__pycache__" in relative.parts or path.suffix == ".pyc" or path.is_dir() and not path.is_symlink():
            continue
        if path.is_symlink():
            raise ValueError(f"{prefix}/{relative.as_posix()}: an artifact holds no symlinks")
        found[f"{prefix}/{relative.as_posix()}"] = _read(path)
    return found


def _read(path: Path) -> bytes:
    if path.is_symlink():
        raise ValueError(f"{path}: an artifact holds no symlinks")
    return path.read_bytes()


def _dependencies(project_dir: Path) -> list[str]:
    """``[project] dependencies`` but the SDK, which the runtime supplies."""
    pyproject = project_dir / "pyproject.toml"
    if not pyproject.is_file():
        return []
    declared = tomllib.loads(pyproject.read_text()).get("project", {}).get("dependencies", [])
    return [requirement for requirement in declared if _name(requirement) != "aisolate"]


def _name(requirement: str) -> str:
    """A requirement's distribution name, normalized (PEP 503)."""
    return re.sub(r"[-_.]+", "-", re.split(r"[\s\[<>=!~;@(]", requirement.strip(), maxsplit=1)[0]).lower()


def _pin(line: str) -> tuple[str, str]:
    """``name==version[ ; marker]`` as ``(name, version)``."""
    return _name(line), line.partition("==")[2].split(";")[0].strip()


def _resolve(uv: str, requirements: Sequence[str], constraints: Sequence[str], linux: Sequence[str]) -> dict[str, str]:
    """What uv resolves ``requirements`` to under ``constraints``, name to version; CalledProcessError if nothing."""
    with tempfile.TemporaryDirectory() as scratch:
        file = Path(scratch, "constraints.txt")
        file.write_text("".join(line + "\n" for line in constraints))
        compile_ = [uv, "pip", "compile", "-", "-c", str(file), "--no-header", "--no-annotate", "--quiet", *linux]
        done = subprocess.run(compile_, input="\n".join(requirements), capture_output=True, text=True, check=True)
    return dict(_pin(line) for line in done.stdout.splitlines() if line.strip() and not line.startswith(("#", "-")))


def _unresolvable(uv: str, requirements: Sequence[str], pins: Sequence[str], linux: Sequence[str], error: str,
                  arch: str) -> str:
    """Why the dependencies do not resolve against the pinned set: a package without a wheel, else each
    pinned package the project needs at another version, else uv's own words."""
    error = " ".join(error.split())  # uv wraps its lines
    if missing := sorted(set(_NO_WHEEL.findall(error))):
        return "\n".join(f"{name} has no Linux wheel for Python 3.13 on {arch}: only binary wheels are vendored"
                         for name in missing)
    try:
        free = _resolve(uv, requirements, [], linux)
    except subprocess.CalledProcessError:
        return f"the dependencies do not resolve for Linux on {arch}: {error}"
    pinned = dict(map(_pin, pins))
    line = {_pin(pin)[0]: pin for pin in pins}
    differ = [name for name, version in sorted(free.items()) if pinned.get(name, version) != version]
    # Unconstrained, uv prefers the newest version: a clash is one the pinned version alone fails on.
    clashes = [name for name in differ if not _resolves(uv, requirements, [line[name]], linux)] or differ
    return "\n".join(
        f"{name} {free[name]} (the project's dependencies) clashes with {name} {pinned[name]}"
        " (the runtime's pinned set): depend on a version the runtime has"
        for name in clashes
    ) or error


def _resolves(uv: str, requirements: Sequence[str], constraints: Sequence[str], linux: Sequence[str]) -> bool:
    try:
        _resolve(uv, requirements, constraints, linux)
    except subprocess.CalledProcessError:
        return False
    return True


def _distribution(metadata: Path) -> dict[str, str]:
    fields = dict(
        line.split(": ", 1) for line in metadata.read_text().splitlines() if line.startswith(("Name: ", "Version: "))
    )
    return {"name": fields.get("Name", metadata.parent.name), "version": fields.get("Version", "")}
