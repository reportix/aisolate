import json
import socket
import subprocess
import sys
import zipfile
from collections.abc import Sequence
from pathlib import Path
from types import SimpleNamespace

import pytest

from aisolate import Column, ColumnType, Entity, build

PLUGIN = '''
from aisolate import Column, ColumnType, Entity, Result, ToolPlugin, tool
from aisolate.arguments import schema


class HrTools(ToolPlugin):
    name = "hr-tools"
    data_source = "hr"
    identity_attribute = "employee_id"
    identity_join = "SELECT id FROM hr.employees WHERE lower(email) = lower(%s)"
    entities = (
        Entity("hr.employees", "hr.employees e", "employee_id",
               Column("employee_id", "e.id", ColumnType.NUMBER),
               Column("full_name", "e.full_name", ColumnType.TEXT),
               subject_name="full_name"),
    )

    @tool("read_my_records", "Read my records", schema({"entity": {"type": "string"}}, ["entity"]))
    def read_my_records(self, query, arguments):
        return query.read(arguments["entity"])
'''

HANDLERS = '''
from aisolate import Verdict, context_preparator, verifier


@context_preparator(order=1, description="Read HR documents", data_sources=["hr"])
class HrDocuments:
    def prepare(self, exchange, aisolate):
        exchange.add_context("x")


@verifier(description="Check figures")
class FiguresInEvidence:
    def verify(self, answer, evidence, aisolate):
        return Verdict.pass_()
'''


def _project(root: Path, package: str, files: dict[str, str]) -> Path:
    for name, text in {"__init__.py": "", **files}.items():
        path = root / "src" / package / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    (root / "rules").mkdir(exist_ok=True)
    (root / "rules" / "hr.employees.json").write_text('{"entity": "hr.employees", "rules": []}')
    return root


@pytest.fixture
def project(tmp_path: Path, request: pytest.FixtureRequest) -> Path:
    package = f"proj_{request.node.name[-40:].replace('[', '_').replace(']', '_').replace('-', '_')}".lower()
    root = _project(tmp_path / "p", package, {"tools/__init__.py": "", "tools/hr.py": PLUGIN, "post.py": HANDLERS})
    yield root
    sys.path[:] = [p for p in sys.path if not p.startswith(str(tmp_path))]
    for name in [m for m in sys.modules if m.startswith(package)]:
        del sys.modules[name]


def _package(project: Path) -> str:
    return next((project / "src").iterdir()).name


def test_given_project_when_built_then_zip_holds_manifest_sources_and_rules(project: Path) -> None:
    # Arrange
    (project / "src" / _package(project) / "__pycache__").mkdir()
    (project / "src" / _package(project) / "__pycache__" / "x.pyc").write_bytes(b"0")

    # Act
    artifact = build.build(project)

    # Assert
    package = _package(project)
    names = zipfile.ZipFile(artifact).namelist()
    assert names == sorted(names)
    assert names == [
        "aisolate-manifest.json",
        "rules/hr.employees.json",
        f"src/{package}/__init__.py",
        f"src/{package}/post.py",
        f"src/{package}/tools/__init__.py",
        f"src/{package}/tools/hr.py",
    ]
    assert artifact == project / "build" / "aisolate" / f"{package}.zip"


def test_given_project_when_built_then_manifest_lists_plugins_tools_entities_handlers(project: Path) -> None:
    # Arrange
    package = _package(project)

    # Act
    manifest = json.loads(zipfile.ZipFile(build.build(project)).read("aisolate-manifest.json"))

    # Assert
    assert manifest["package"] == package and manifest["language"] == "python" and manifest["schema"] == 1
    assert manifest["plugins"] == [
        {"name": "hr-tools", "class": f"{package}.tools.hr.HrTools", "data_source": "hr",
         "identity_attribute": "employee_id", "entities": ["hr.employees"], "tools": ["read_my_records"]}
    ]
    assert manifest["tools"][0]["input_schema"]["required"] == ["entity"]
    assert manifest["entities"][0]["columns"][1] == {
        "name": "full_name", "expression": "e.full_name", "type": "text", "unit": None, "unit_column": None, "decimals": None
    }
    assert [(h["name"], h["stage"], h["order"], h.get("data_sources")) for h in manifest["handlers"]] == [
        ("FiguresInEvidence", "answerVerifier", 0, None),
        ("HrDocuments", "contextPreparator", 1, ["hr"]),
    ]
    assert manifest["rules"] == ["rules/hr.employees.json"] and manifest["vendored"] == []


def test_given_fact_column_when_entity_written_then_fact_only_on_it() -> None:
    # Arrange: a manifest built before facts existed must still match its code
    entity = Entity("ehr.medications", "ehr.medications m", "id",
                    Column("id", "m.id", ColumnType.NUMBER), Column("drug", "m.drug", ColumnType.TEXT, fact=True))

    # Act
    written = build.entity_json(entity, SimpleNamespace(name="ehr-tools", data_source="ehr"))

    # Assert
    assert [c.get("fact") for c in written["columns"]] == [None, True]
    assert "fact" not in written["columns"][0]


def test_given_same_source_when_built_twice_then_same_bytes(project: Path, tmp_path: Path) -> None:
    # Arrange / Act
    first = build.build(project, out=tmp_path / "a.zip").read_bytes()
    second = build.build(project, out=tmp_path / "b.zip").read_bytes()

    # Assert
    assert first == second


@pytest.mark.parametrize(("arch", "recorded"), [("x86_64", "x86_64"), ("amd64", "x86_64"), ("arm64", "aarch64")])
def test_given_arch_when_built_then_manifest_records_the_cpu(project: Path, arch: str, recorded: str) -> None:
    # Arrange / Act
    artifact = build.build(project, arch=arch)

    # Assert
    assert json.loads(zipfile.ZipFile(artifact).read("aisolate-manifest.json"))["arch"] == recorded


def test_given_catalog_and_its_manifest_when_checked_then_no_problem(project: Path) -> None:
    # Arrange
    manifest = json.loads(zipfile.ZipFile(build.build(project)).read("aisolate-manifest.json"))

    # Act
    problems = build.check(build.discover(_package(project)), manifest)

    # Assert
    assert problems == []


def test_given_manifest_that_differs_when_checked_then_every_difference_named(project: Path) -> None:
    # Arrange
    manifest = json.loads(zipfile.ZipFile(build.build(project)).read("aisolate-manifest.json"))
    manifest["tools"][0]["description"] = "Something else"
    manifest["handlers"] = manifest["handlers"][:1] + [{**manifest["handlers"][1], "order": 9}]
    manifest["entities"].append({**manifest["entities"][0], "name": "hr.ghost"})

    # Act
    problems = build.check(build.discover(_package(project)), manifest)

    # Assert
    assert problems == [
        "tool read_my_records differs from the manifest: description",
        "entity hr.ghost is in the manifest but not in the code",
        "handler HrDocuments differs from the manifest: order",
    ]


@pytest.mark.parametrize(
    ("source", "problem"),
    [
        (PLUGIN.replace('data_source = "hr"', ""), "plugin hr-tools declares entities but names no data source"),
        (PLUGIN + "\nclass Again(HrTools):\n    pass\n", "tool read_my_records is published twice"),
        (PLUGIN.replace('"employee_id",\n', '"nope",\n', 1), "entity hr.employees: row key nope is not a column"),
        (PLUGIN.replace('"full_name", "e.full_name"', '"Full_Name", "e.full_name"').replace('subject_name="full_name"', ""),
         "entity hr.employees: column Full_Name must be lowercase letters, digits and _, starting with a letter or _"),
        (PLUGIN.replace('name = "hr-tools"', 'name = " "'), "a plugin has no name"),
        (PLUGIN + "\nclass Broken(ToolPlugin):\n    name = 'b'\n    def __init__(self):\n        raise RuntimeError('no env')\n",
         "could not be created: no env"),
    ],
)
def test_given_inconsistent_code_when_discovered_then_value_error_names_it(tmp_path: Path, source: str, problem: str) -> None:
    # Arrange
    package = f"bad_{abs(hash(source))}"
    _project(tmp_path, package, {"tools.py": source})
    sys.path.insert(0, str(tmp_path / "src"))

    # Act / Assert
    try:
        with pytest.raises(ValueError, match=problem.replace("(", r"\(")):
            build.discover(package)
    finally:
        sys.path.remove(str(tmp_path / "src"))


def test_given_two_packages_when_built_then_value_error(tmp_path: Path) -> None:
    # Arrange
    for package in ("one", "two"):
        (tmp_path / "src" / package).mkdir(parents=True)
        (tmp_path / "src" / package / "__init__.py").write_text("")

    # Act / Assert
    with pytest.raises(ValueError, match="exactly one package, found: one, two"):
        build.build(tmp_path)


def test_given_project_when_main_runs_then_path_and_digest_printed(project: Path, capsys: pytest.CaptureFixture[str]) -> None:
    # Arrange / Act
    code = build.main([str(project)])

    # Assert
    path, digest = capsys.readouterr().out.splitlines()
    assert code == 0
    assert digest == build.digest(Path(path)) and digest.startswith("sha256:")


def test_given_broken_project_when_main_runs_then_exit_1_and_problem_on_stderr(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    # Arrange / Act
    code = build.main([str(tmp_path)])

    # Assert
    assert code == 1
    assert "exactly one package" in capsys.readouterr().err


# Vendoring: a local wheel index stands in for PyPI (UV_NO_INDEX, UV_FIND_LINKS), so these run offline.


def _wheel(index: Path, name: str, version: str, tag: str = "py3-none-any", requires: Sequence[str] = (),
           body: str = "") -> None:
    dist = f"{name}-{version}.dist-info"
    files = {
        f"{name}/__init__.py": body,
        f"{dist}/METADATA": f"Metadata-Version: 2.1\nName: {name}\nVersion: {version}\n"
        + "".join(f"Requires-Dist: {r}\n" for r in requires),
        f"{dist}/WHEEL": f"Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: {tag}\n",
    }
    files[f"{dist}/RECORD"] = "".join(f"{f},,\n" for f in files) + f"{dist}/RECORD,,\n"
    with zipfile.ZipFile(index / f"{name}-{version}-{tag}.whl", "w") as wheel:
        for path, text in files.items():
            wheel.writestr(path, text)


@pytest.fixture
def index(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    wheels = tmp_path / "wheels"
    wheels.mkdir()
    _wheel(wheels, "pyyaml", "6.0.3")
    _wheel(wheels, "pyyaml", "5.4.1")
    for name, value in {"UV_NO_INDEX": "1", "UV_FIND_LINKS": str(wheels), "UV_NO_CACHE": "1"}.items():
        monkeypatch.setenv(name, value)
    monkeypatch.delenv("AISOLATE_ARCH", raising=False)
    return wheels


def _depends(project: Path, *requirements: str) -> None:
    listed = ", ".join(f'"{r}"' for r in ["aisolate", *requirements])
    (project / "pyproject.toml").write_text(f'[project]\nname = "p"\nversion = "0.1.0"\ndependencies = [{listed}]\n')


def _vendor(artifact: Path) -> tuple[list[str], list[dict[str, str]]]:
    archive = zipfile.ZipFile(artifact)
    names = [n for n in archive.namelist() if n.startswith("vendor/")]
    return names, json.loads(archive.read("aisolate-manifest.json"))["vendored"]


@pytest.mark.parametrize("pyproject", [None, '[project]\ndependencies = ["aisolate"]\n',
                                       '[project]\ndependencies = ["aisolate @ file:libs/aisolate-sdk"]\n'],
                         ids=["no-pyproject", "sdk", "sdk-by-path"])
def test_given_no_dependency_beyond_sdk_when_built_without_uv_then_vendor_empty(
        project: Path, monkeypatch: pytest.MonkeyPatch, pyproject: str | None) -> None:
    # Arrange
    if pyproject is not None:
        (project / "pyproject.toml").write_text(pyproject)
    monkeypatch.delenv("UV", raising=False)
    monkeypatch.setenv("PATH", "")

    # Act
    names, vendored = _vendor(build.build(project))

    # Assert
    assert names == [] and vendored == []


def test_given_dependency_and_no_uv_when_built_then_refused_install_uv(project: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # Arrange
    _depends(project, "tabulate==0.10.0")
    monkeypatch.delenv("UV", raising=False)
    monkeypatch.setenv("PATH", "")

    # Act / Assert
    with pytest.raises(ValueError, match=r"install uv to vendor dependencies \(tabulate==0.10.0\)"):
        build.build(project)


def test_given_pure_python_dependency_when_built_then_vendored_and_in_manifest(project: Path, index: Path) -> None:
    # Arrange: tinydep needs pyyaml, which the runtime pins, so only tinydep travels
    _wheel(index, "tinydep", "1.0", requires=["pyyaml>=6"], body="VALUE = 42\n")
    _depends(project, "tinydep", "PyYAML")
    (project / "src" / _package(project) / "uses.py").write_text("import tinydep\n")

    # Act
    names, vendored = _vendor(build.build(project, arch="aarch64"))

    # Assert
    dist = "vendor/tinydep-1.0.dist-info"
    assert names == [f"{dist}/INSTALLER", f"{dist}/METADATA", f"{dist}/REQUESTED", f"{dist}/WHEEL",
                     "vendor/tinydep/__init__.py"]
    assert vendored == [{"name": "tinydep", "version": "1.0"}]


@pytest.mark.parametrize("requirements", [["pyyaml==5.4.1"], ["oldyaml"]], ids=["direct", "transitive"])
def test_given_dependency_on_another_version_of_a_pinned_package_when_built_then_refused_naming_both(
        project: Path, index: Path, requirements: list[str]) -> None:
    # Arrange: the runtime pins pyyaml 6.0.3
    _wheel(index, "oldyaml", "1.0", requires=["pyyaml<6"])
    _depends(project, *requirements)

    # Act / Assert
    with pytest.raises(ValueError) as refused:
        build.build(project, arch="aarch64")
    assert str(refused.value) == ("pyyaml 5.4.1 (the project's dependencies) clashes with pyyaml 6.0.3"
                                  " (the runtime's pinned set): depend on a version the runtime has")


@pytest.mark.parametrize("tag", ["py3-none-macosx_11_0_arm64", "py3-none-manylinux_2_28_x86_64"], ids=["macos", "other-arch"])
def test_given_dependency_without_linux_wheel_when_built_then_refused_naming_it(project: Path, index: Path, tag: str) -> None:
    # Arrange
    _wheel(index, "nativepkg", "1.0", tag=tag)
    _depends(project, "nativepkg")

    # Act / Assert
    with pytest.raises(ValueError, match="^nativepkg has no Linux wheel for Python 3.13 on aarch64"):
        build.build(project, arch="aarch64")


@pytest.mark.parametrize(
    ("arch", "env", "machine", "expected"),
    [("x86_64", None, "arm64", "x86_64"), ("arm64", None, "x86_64", "aarch64"), (None, "x86_64", "arm64", "x86_64"),
     (None, None, "arm64", "aarch64"), (None, None, "x86_64", "x86_64")],
    ids=["option", "option-alias", "env", "apple-silicon", "intel"],
)
def test_given_arch_choice_when_built_then_wheel_of_that_arch_vendored(
        project: Path, index: Path, monkeypatch: pytest.MonkeyPatch, arch: str | None, env: str | None, machine: str,
        expected: str) -> None:
    # Arrange
    for cpu in ("x86_64", "aarch64"):
        _wheel(index, "archpkg", "1.0", tag=f"py3-none-manylinux_2_28_{cpu}", body=f"CPU = {cpu!r}\n")
    _depends(project, "archpkg")
    if env is not None:
        monkeypatch.setenv("AISOLATE_ARCH", env)
    monkeypatch.setattr(build.platform, "machine", lambda: machine)

    # Act
    artifact = build.build(project, arch=arch)

    # Assert
    assert zipfile.ZipFile(artifact).read("vendor/archpkg/__init__.py").decode() == f"CPU = {expected!r}\n"


def test_given_unknown_arch_when_built_then_refused(project: Path, index: Path) -> None:
    # Arrange
    _depends(project, "pyyaml")

    # Act / Assert
    with pytest.raises(ValueError, match="cannot vendor for riscv64: set --arch or AISOLATE_ARCH to x86_64 or aarch64"):
        build.build(project, arch="riscv64")


def _online() -> bool:
    try:
        socket.create_connection(("pypi.org", 443), timeout=3).close()
    except OSError:
        return False
    return True


@pytest.mark.skipif(not _online(), reason="needs pypi.org")
def test_given_tabulate_from_pypi_when_built_then_vendored(project: Path) -> None:
    # Arrange
    _depends(project, "tabulate==0.10.0")

    # Act
    names, vendored = _vendor(build.build(project))

    # Assert
    assert "vendor/tabulate/__init__.py" in names
    assert vendored == [{"name": "tabulate", "version": "0.10.0"}]
