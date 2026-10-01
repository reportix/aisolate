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


def _write(directory: Path, files: dict[str, str]) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    for name, text in files.items():
        (directory / name).write_text(text)
    return directory


def _with_airlock(change: Any) -> dict[str, str]:
    files = dict(RENDERED["releases"][0]["files"])
    files[AIRLOCK_FILE] = change(files[AIRLOCK_FILE])
    return files


@pytest.mark.parametrize("release", RENDERED["releases"], ids=[r["revision"] for r in RENDERED["releases"]])
def test_given_controller_rendered_files_when_loaded_then_revision_is_the_one_java_rendered(tmp_path: Path, release: dict[str, Any]) -> None:
    # Arrange
    directory = _write(tmp_path / "config", release["files"])

    # Act
    configuration = config.load(directory, release["revision"])

    # Assert
    assert configuration.revision == release["revision"]


def test_given_rendered_files_when_loaded_then_airlock_resolved_with_its_space(tmp_path: Path) -> None:
    # Arrange
    directory = _write(tmp_path / "config", RENDERED["releases"][0]["files"])

    # Act
    airlock = config.load(directory, None).airlock("hr-assistant")

    # Assert
    assert (airlock.space, airlock.name, airlock.tools) == ("hr-acme", "hr-assistant", ("read_records", "aggregate_records"))
    assert airlock.handlers[Stage.CONTEXT_PREPARATOR] == ("HrDocumentsContextPreparator",)
    assert airlock.handlers[Stage.ANSWER_VERIFIER] == ("SalaryVerifier",)
    assert airlock.code.language == "java" and airlock.code.digest.startswith("sha256:")
    assert (airlock.llm, [llm.id for llm in airlock.llms]) == ("granite", ["granite", "nomic", "qwen"])
    assert airlock.access.callers and airlock.access.users and airlock.access.clients == ()
    assert [ds.id for ds in airlock.data_sources] == ["acme_hr", "audit_log", "hr-documents"]


def test_given_other_revision_when_loaded_then_mismatch_refused(tmp_path: Path) -> None:
    # Arrange
    directory = _write(tmp_path / "config", RENDERED["releases"][0]["files"])

    # Act / Assert
    with pytest.raises(ConfigurationError, match=f"revision mismatch: AISOLATE_CONFIG_REVISION says 000000000000, the documents hash to {RENDERED['releases'][0]['revision']}"):
        config.load(directory, "000000000000")


@pytest.mark.parametrize(
    ("change", "problem"),
    [
        (lambda t: t.replace("  space: hr-acme\n", "  space: hr-acme\n  spase: x\n", 1), "spec.spase: unknown field"),
        (lambda t: t.replace("  space: hr-acme\n", "  space: hr-acme\n  space: other\n", 1), "duplicate key space"),
        (lambda t: t.replace("  space: hr-acme\n", "  space: nowhere\n", 1), "spec.space nowhere is no Space"),
        (lambda t: t.replace("kind: Airlock", "kind: AirlockVersion"), "unknown kind AirlockVersion"),
        (lambda t: t.replace("aisolate.dev/v1", "aisolate.dev/v2"), "apiVersion aisolate.dev/v2 is not aisolate.dev/v1"),
        (lambda t: "".join(t.rsplit("    - aggregate_records\n", 1)), "spec.tools aggregate_records not in the code"),
        (lambda t: t.replace("  llm: granite\n", "  llm: gemma\n"), "llm gemma is no model of it, of Space hr-acme or of AIsolate"),
    ],
)
def test_given_broken_airlock_document_when_loaded_then_refused_naming_it(tmp_path: Path, change: Any, problem: str) -> None:
    # Arrange
    directory = _write(tmp_path / "config", _with_airlock(change))

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
    files = _with_airlock(lambda t: t.replace("  jar:\n", "  jar:\n    language: python\n"))

    # Act
    airlock = config.load(_write(tmp_path / "c", files), None).airlock("hr-assistant")

    # Assert
    assert airlock.code.language == "python"


@pytest.mark.parametrize("missing", ["  llm: granite\n", "  prompt: |\n    You are the Acme HR assistant. All amounts are in EUR.\n"],
                         ids=["no-llm", "no-prompt"])
def test_given_airlock_without_llm_or_prompt_when_loaded_then_valid_but_not_served(tmp_path: Path, missing: str) -> None:
    # Arrange: aisolate deploy brings them; until then the airlock runs nothing
    files = _with_airlock(lambda t: t.replace(missing, ""))
    assert files[AIRLOCK_FILE] != RENDERED["releases"][0]["files"][AIRLOCK_FILE]

    # Act
    configuration = config.load(_write(tmp_path / "c", files), None)

    # Assert
    assert configuration.airlock("hr-assistant") is None and configuration.airlocks == {}


def test_given_a_date_like_value_as_java_renders_it_when_loaded_then_read_as_text(tmp_path: Path) -> None:
    # Arrange: Java writes 2026-10-26 unquoted; YAML 1.1 would read a date
    files = _with_airlock(lambda t: t.replace("  displayName: Acme HR assistant\n", "  displayName: 2026-10-26\n"))

    # Act
    configuration = config.load(_write(tmp_path / "c", files), None)

    # Assert
    assert configuration.airlock("hr-assistant") is not None


def _with_clients(clients: str) -> dict[str, str]:
    return _with_airlock(lambda t: t.replace("    callers:\n", f"    clients: {clients}\n    callers:\n"))


def test_given_airlock_with_clients_when_loaded_then_access_carries_them(tmp_path: Path) -> None:
    # Arrange
    files = _with_clients("[knime, n8n]")

    # Act
    airlock = config.load(_write(tmp_path / "c", files), None).airlock("hr-assistant")

    # Assert
    assert airlock.access.clients == ("knime", "n8n")


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
