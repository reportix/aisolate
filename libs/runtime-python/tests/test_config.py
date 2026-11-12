import json
import re
from pathlib import Path
from typing import Any

import pytest

from aisolate import Stage
from aisolate_runtime import config
from aisolate_runtime.config import ConfigurationError

RENDERED = json.loads((Path(__file__).parent / "fixtures" / "rendered-documents.json").read_text())
AIRLOCK_FILE = "Airlock-hr-assistant.yaml"
VERSION_FILE = "AirlockVersion-hr-assistant-v1.yaml"


def _write(directory: Path, files: dict[str, str]) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    for name, text in files.items():
        (directory / name).write_text(text)
    return directory


def _with(file: str, change: Any) -> dict[str, str]:
    files = dict(RENDERED["releases"][0]["files"])
    files[file] = change(files[file])
    return files


@pytest.mark.parametrize("release", RENDERED["releases"], ids=[r["revision"] for r in RENDERED["releases"]])
def test_given_controller_rendered_files_when_loaded_then_revision_is_the_one_java_rendered(tmp_path: Path, release: dict[str, Any]) -> None:
    # Arrange: four kinds; Java sorts them as strings, Airlock before AirlockVersion
    directory = _write(tmp_path / "config", release["files"])

    # Act
    configuration = config.load(directory, release["revision"])

    # Assert
    assert configuration.revision == release["revision"]


def test_given_rendered_files_when_loaded_then_version_resolved_from_its_airlock_and_space(tmp_path: Path) -> None:
    # Arrange
    directory = _write(tmp_path / "config", RENDERED["releases"][0]["files"])

    # Act
    configuration = config.load(directory, None)
    version = configuration.version("hr-assistant", "v1")

    # Assert
    assert list(configuration.versions) == ["hr-assistant-v1"] and configuration.version("hr-assistant", "v2") is None
    assert (version.space, version.airlock, version.label, version.name) == ("hr-acme", "hr-assistant", "v1", "hr-assistant-v1")
    assert version.tools == ("read_records", "aggregate_records")
    assert version.handlers[Stage.CONTEXT_PREPARATOR] == ("HrDocumentsContextPreparator",)
    assert version.handlers[Stage.ANSWER_VERIFIER] == ("SalaryVerifier",)
    assert version.code.language == "java" and version.code.digest.startswith("sha256:")
    assert (version.llm, [llm.id for llm in version.llms]) == ("granite", ["granite", "nomic", "qwen"])
    assert version.access.roles == ("hr", "employee") and version.access.callers == ("*",) and version.access.clients == ()
    assert [ds.id for ds in version.data_sources] == ["acme_hr", "audit_log", "hr-documents"]


def test_given_other_revision_when_loaded_then_mismatch_refused(tmp_path: Path) -> None:
    # Arrange
    directory = _write(tmp_path / "config", RENDERED["releases"][0]["files"])

    # Act / Assert
    with pytest.raises(ConfigurationError, match=f"revision mismatch: AISOLATE_CONFIG_REVISION says 000000000000, the documents hash to {RENDERED['releases'][0]['revision']}"):
        config.load(directory, "000000000000")


@pytest.mark.parametrize(
    ("file", "change", "problem"),
    [
        (VERSION_FILE, lambda t: t.replace("  airlock: hr-assistant\n", "  airlock: hr-assistant\n  airlok: x\n", 1), "spec.airlok: unknown field"),
        (VERSION_FILE, lambda t: t.replace("  airlock: hr-assistant\n", "  airlock: hr-assistant\n  airlock: other\n", 1), "duplicate key airlock"),
        (VERSION_FILE, lambda t: t.replace("  airlock: hr-assistant\n", "  airlock: nowhere\n", 1), "spec.airlock nowhere is no Airlock"),
        (VERSION_FILE, lambda t: t.replace("  version: v1\n", "  version: v2\n", 1), "metadata.name must be hr-assistant-v2"),
        (VERSION_FILE, lambda t: t.replace("kind: AirlockVersion", "kind: Pipeline"), "unknown kind Pipeline"),
        (VERSION_FILE, lambda t: t.replace("aisolate.dev/v1", "aisolate.dev/v2"), "apiVersion aisolate.dev/v2 is not aisolate.dev/v1"),
        (VERSION_FILE, lambda t: "".join(t.rsplit("    - aggregate_records\n", 1)), "spec.tools aggregate_records not in the code"),
        (VERSION_FILE, lambda t: t.replace("  llm: granite\n", "  llm: gemma\n"), "llm gemma is no model of it, of Airlock hr-assistant, of Space hr-acme or of AIsolate"),
        (VERSION_FILE, lambda t: t.replace("  llm: granite\n", ""), "AirlockVersion hr-assistant-v1: spec.llm is required"),
        (VERSION_FILE, lambda t: t.replace("  prompt: |\n    You are the Acme HR assistant. All amounts are in EUR.\n", ""), "AirlockVersion hr-assistant-v1: spec.prompt is required"),
        # A data source needs a login: a version holds none
        (VERSION_FILE, lambda t: t.replace("  llms: []\n", "  dataSources: []\n"), "spec.dataSources: unknown field"),
        (AIRLOCK_FILE, lambda t: t.replace("  space: hr-acme\n", "  space: nowhere\n", 1), "spec.space nowhere is no Space"),
        # What runs is the version's
        (AIRLOCK_FILE, lambda t: t.replace("  space: hr-acme\n", "  space: hr-acme\n  llm: granite\n", 1), "Airlock hr-assistant: spec.llm: unknown field"),
    ],
)
def test_given_broken_document_when_loaded_then_refused_naming_it(tmp_path: Path, file: str, change: Any, problem: str) -> None:
    # Arrange
    directory = _write(tmp_path / "config", _with(file, change))

    # Act / Assert
    with pytest.raises(ConfigurationError, match=problem):
        config.load(directory, None)


@pytest.mark.parametrize("blank", [None, "", "  "])
def test_given_no_directory_when_loaded_then_refused(blank: str | None) -> None:
    # Arrange / Act / Assert
    with pytest.raises(ConfigurationError, match="AISOLATE_CONFIG_DIR is required"):
        config.load(blank, None)


def test_given_python_code_when_loaded_then_language_read(tmp_path: Path) -> None:
    # Arrange
    files = _with(VERSION_FILE, lambda t: t.replace("  jar:\n", "  jar:\n    language: python\n"))

    # Act
    version = config.load(_write(tmp_path / "c", files), None).version("hr-assistant", "v1")

    # Assert
    assert version.code.language == "python"


def test_given_airlock_without_versions_when_loaded_then_valid_and_nothing_served(tmp_path: Path) -> None:
    # Arrange: the AIsolate UI creates an airlock before its first deploy
    files = dict(RENDERED["releases"][0]["files"])
    del files[VERSION_FILE]

    # Act
    configuration = config.load(_write(tmp_path / "c", files), None)

    # Assert
    assert configuration.version("hr-assistant", "v1") is None and configuration.versions == {}


def test_given_a_date_like_version_as_java_renders_it_when_loaded_then_read_as_text(tmp_path: Path) -> None:
    # Arrange: Java writes 2026-10-26 unquoted; YAML 1.1 would read a date
    files = _with(VERSION_FILE, lambda t: t.replace("  name: hr-assistant-v1\n", "  name: hr-assistant-2026-10-26\n")
                  .replace("  version: v1\n", "  version: 2026-10-26\n"))

    # Act
    version = config.load(_write(tmp_path / "c", files), None).version("hr-assistant", "2026-10-26")

    # Assert
    assert version.name == "hr-assistant-2026-10-26"


def _with_clients(clients: str) -> dict[str, str]:
    return _with(AIRLOCK_FILE, lambda t: t.replace("    callers:\n", f"    clients: {clients}\n    callers:\n"))


def test_given_airlock_with_clients_when_loaded_then_its_versions_access_carries_them(tmp_path: Path) -> None:
    # Arrange
    files = _with_clients("[knime, n8n]")

    # Act
    version = config.load(_write(tmp_path / "c", files), None).version("hr-assistant", "v1")

    # Assert
    assert version.access.clients == ("knime", "n8n")


@pytest.mark.parametrize(
    ("clients", "problem"),
    [
        ("['*']", "Airlock hr-assistant: spec.access.clients * is no wildcard: list each client id"),
        ("[knime, knime]", "Airlock hr-assistant: spec.access.clients lists knime twice"),
        ("[' ']", "Airlock hr-assistant: spec.access.clients must not contain a blank client id"),
        ("[null]", "Airlock hr-assistant: spec.access.clients must not contain a blank client id"),
        ("['kn ime']", 'Airlock hr-assistant: spec.access.clients "kn ime" has a space in it'),
        ("[7]", "Airlock hr-assistant: spec.access.clients[0] must be a string"),
    ],
)
def test_given_unusable_client_when_loaded_then_refused(tmp_path: Path, clients: str, problem: str) -> None:
    # Arrange
    directory = _write(tmp_path / "c", _with_clients(clients))

    # Act / Assert
    with pytest.raises(ConfigurationError, match=re.escape(problem)):
        config.load(directory, None)


def test_given_clients_on_a_space_when_loaded_then_unknown_field(tmp_path: Path) -> None:
    # Arrange
    files = dict(RENDERED["releases"][0]["files"])
    space = next(name for name in files if name.startswith("Space-"))
    files[space] = files[space].replace("  access:\n", "  access:\n    clients: [knime]\n", 1)

    # Act / Assert
    with pytest.raises(ConfigurationError, match="spec.access.clients: unknown field"):
        config.load(_write(tmp_path / "c", files), None)


@pytest.mark.parametrize(("roles", "expected"), [
    (["ceo", "Finance", "finance"], []),
    ([], []),
    (["*"], ["* is no role: list each role; only callers take * for any"]),
    (["ceo", " "], ["must not contain a blank role"]),
    (["hr admin"], ['"hr admin" has a space in it']),
    (["ceo", "hr", "ceo"], ["lists ceo twice"]),
], ids=["distinct", "none", "star", "blank", "whitespace", "duplicate"])
def test_given_roles_when_checked_then_problems_mirror_names_role_problems(roles, expected):
    # Arrange / Act
    problems = config._role_problems(roles)

    # Assert
    assert problems == expected
