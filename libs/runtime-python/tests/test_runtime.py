import hashlib
import stat
import sys
import zipfile
from pathlib import Path

import pytest
from starlette.testclient import TestClient

from conftest import serving

from aisolate_runtime import artifact, telemetry
from aisolate_runtime.artifact import ArtifactError
from aisolate_runtime.datasources import Pools, parse_jdbc_url


def _zip(path: Path, entries: dict[str, bytes], symlink: str | None = None) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
        if symlink:
            info = zipfile.ZipInfo(symlink)
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(info, "/etc/passwd")
    return path


def _digest(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.parametrize("case", ["upper", "list"])
def test_given_pinned_zip_when_checked_then_found_whatever_the_case_and_list(tmp_path: Path, case: str) -> None:
    # Arrange
    zip_path = _zip(tmp_path / "plugins" / "a.zip", {"x": b"1"})
    pins = _digest(zip_path).upper() if case == "upper" else f" sha256:{'0' * 64} , {_digest(zip_path)} "

    # Act
    found = artifact.pinned(tmp_path / "plugins", pins)

    # Assert
    assert found == zip_path


@pytest.mark.parametrize("pins", [None, "", "sha256:" + "0" * 64])
def test_given_unpinned_zip_when_checked_then_refused_naming_its_digest(tmp_path: Path, pins: str | None) -> None:
    # Arrange
    zip_path = _zip(tmp_path / "plugins" / "a.zip", {"x": b"1"})

    # Act / Assert
    with pytest.raises(ArtifactError, match=f"plugin artifact a.zip has digest {_digest(zip_path)} which is not in AISOLATE_PLUGIN_DIGESTS"):
        artifact.pinned(tmp_path / "plugins", pins)


@pytest.mark.parametrize(
    ("entries", "symlink", "problem"),
    [
        ({"../evil.py": b""}, None, "leaves the artifact"),
        ({"/etc/evil.py": b""}, None, "leaves the artifact"),
        ({"src/a/../../x": b""}, None, "leaves the artifact"),
        ({}, "src/link", "is a symlink"),
    ],
)
def test_given_unsafe_entry_when_extracted_then_refused(tmp_path: Path, entries: dict[str, bytes], symlink: str | None, problem: str) -> None:
    # Arrange
    zip_path = _zip(tmp_path / "a.zip", entries, symlink)

    # Act / Assert
    with pytest.raises(ArtifactError, match=problem):
        artifact.extract(zip_path, tmp_path / "code")


def test_given_safe_zip_when_extracted_then_files_under_code_dir_and_old_ones_gone(tmp_path: Path) -> None:
    # Arrange
    (tmp_path / "code").mkdir()
    (tmp_path / "code" / "stale.py").write_text("")
    zip_path = _zip(tmp_path / "a.zip", {"src/p/__init__.py": b"x = 1\n", "rules/e.json": b"{}"})

    # Act
    artifact.extract(zip_path, tmp_path / "code")

    # Assert
    assert sorted(p.relative_to(tmp_path / "code").as_posix() for p in (tmp_path / "code").rglob("*") if p.is_file()) == [
        "rules/e.json", "src/p/__init__.py"]


def test_given_artifact_of_another_python_when_loaded_then_refused(tmp_path: Path) -> None:
    # Arrange
    manifest = b'{"schema": 1, "language": "python", "python": "3.12", "package": "p"}'
    zip_path = _zip(tmp_path / "plugins" / "a.zip", {"aisolate-manifest.json": manifest})

    # Act / Assert
    with pytest.raises(ArtifactError, match="built on Python 3.12"):
        artifact.load(tmp_path / "plugins", _digest(zip_path), tmp_path / "code")


@pytest.mark.parametrize(("built", "node"), [("x86_64", "aarch64"), ("aarch64", "x86_64"), (None, "aarch64")])
def test_given_artifact_of_another_cpu_when_loaded_then_refused_naming_the_rebuild(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, built: str | None, node: str
) -> None:
    # Arrange
    arch = "" if built is None else f', "arch": "{built}"'
    manifest = f'{{"schema": 1, "language": "python", "python": "{sys.version_info.major}.{sys.version_info.minor}"{arch}, "package": "p"}}'
    zip_path = _zip(tmp_path / "plugins" / "a.zip", {"aisolate-manifest.json": manifest.encode()})
    monkeypatch.setattr(artifact.platform, "machine", lambda: {"aarch64": "arm64", "x86_64": "AMD64"}[node])

    # Act / Assert
    with pytest.raises(ArtifactError, match=f"built for {built}, this node is {node}: rebuild with --arch {node}"):
        artifact.load(tmp_path / "plugins", _digest(zip_path), tmp_path / "code")


@pytest.mark.parametrize(
    ("endpoint", "url"),
    [
        (None, None), ("  ", None), ("http://otel:4318", "http://otel:4318/v1/traces"),
        ("http://otel:4318/", "http://otel:4318/v1/traces"), ("http://otel:4318/v1/traces", "http://otel:4318/v1/traces"),
    ],
)
def test_given_otlp_endpoint_when_resolved_then_traces_url(endpoint: str | None, url: str | None) -> None:
    # Arrange / Act / Assert
    assert telemetry.otlp_traces_url(endpoint) == url


@pytest.mark.parametrize(
    ("path", "ready", "status", "body"),
    [
        ("/actuator/health/liveness", False, 200, {"status": "UP"}),
        ("/actuator/health/readiness", True, 200, {"status": "UP"}),
        ("/actuator/health/readiness", False, 503, {"status": "DOWN"}),
        ("/actuator/health", False, 503, {"status": "DOWN"}),
    ],
)
def test_given_readiness_when_probed_then_actuator_answers(path: str, ready: bool, status: int, body: dict) -> None:
    # Arrange
    client = TestClient(telemetry.management_app(lambda: ready))

    # Act
    response = client.get(path)

    # Assert
    assert (response.status_code, response.json()) == (status, body)


def test_given_management_app_when_other_path_or_metrics_then_404_and_prometheus_text() -> None:
    # Arrange
    client = TestClient(telemetry.management_app(lambda: True))

    # Act / Assert
    assert client.get("/mcp").status_code == 404
    assert client.get("/actuator/prometheus").headers["content-type"].startswith("text/plain")


@pytest.mark.parametrize(
    ("url", "target"),
    [
        ("jdbc:postgresql://db.svc:5433/hr", ("db.svc", 5433, "hr", {})),
        ("JDBC:postgresql://db/hr?sslmode=require", ("db", 5432, "hr", {"sslmode": "require"})),
        ("postgresql://db/hr", ("db", 5432, "hr", {})),
    ],
)
def test_given_jdbc_url_when_parsed_then_host_port_database(url: str, target: tuple) -> None:
    # Arrange / Act
    parsed = parse_jdbc_url(url)

    # Assert
    assert (parsed.host, parsed.port, parsed.database, dict(parsed.params)) == target


@pytest.mark.parametrize("url", ["jdbc:mysql://db/hr", "jdbc:postgresql://db", "jdbc:postgresql:///hr"])
def test_given_other_url_when_parsed_then_refused(url: str) -> None:
    # Arrange / Act / Assert
    with pytest.raises(ValueError, match="jdbc:postgresql://host"):
        parse_jdbc_url(url)


@pytest.mark.parametrize(
    ("env", "problem"),
    [
        ({"AISOLATE_DATASOURCE_0_ID": " ", "AISOLATE_DATASOURCE_0_URL": "jdbc:postgresql://d/x"}, "AISOLATE_DATASOURCE_0_ID is blank"),
        ({"AISOLATE_DATASOURCE_0_ID": "a"}, "AISOLATE_DATASOURCE_0_URL is blank"),
        ({"AISOLATE_DATASOURCE_0_ID": "a", "AISOLATE_DATASOURCE_0_URL": "jdbc:postgresql://d/x",
          "AISOLATE_DATASOURCE_1_ID": "a", "AISOLATE_DATASOURCE_1_URL": "jdbc:postgresql://d/y"}, "data source a is defined twice"),
    ],
)
def test_given_broken_data_source_env_when_read_then_start_refused(env: dict[str, str], problem: str) -> None:
    # Arrange / Act / Assert
    with pytest.raises(ValueError, match=problem):
        Pools.from_env(env)


def test_given_contiguous_env_when_read_then_ids_until_first_gap() -> None:
    # Arrange
    env = {"AISOLATE_DATASOURCE_0_ID": "a", "AISOLATE_DATASOURCE_0_URL": "jdbc:postgresql://d/x",
           "AISOLATE_DATASOURCE_2_ID": "c", "AISOLATE_DATASOURCE_2_URL": "jdbc:postgresql://d/z"}

    # Act / Assert
    assert Pools.from_env(env).ids == {"a"}


@pytest.mark.parametrize(("handlers", "startup", "socket_timeout"), [
    (False, {"default_transaction_read_only": "on", "statement_timeout": "12000"}, 15.0),
    (True, {}, 10.0),
], ids=["tool-server", "airlock-handlers"])
def test_given_pool_kind_when_connecting_then_session_as_the_java_pod_opens_it(
        monkeypatch: pytest.MonkeyPatch, handlers: bool, startup: dict, socket_timeout: float) -> None:
    # Arrange
    import socket

    import pg8000.dbapi

    opened: dict = {}

    class Sock:
        def settimeout(self, seconds: float) -> None:
            opened["socket_timeout"] = seconds

    monkeypatch.setattr(socket, "create_connection", lambda address, timeout: Sock())
    monkeypatch.setattr(pg8000.dbapi, "Connection", lambda **options: opened.update(options) or _Rollback())
    pools = Pools.from_env({"AISOLATE_DATASOURCE_0_ID": "hr", "AISOLATE_DATASOURCE_0_URL": "jdbc:postgresql://db/hr"},
                           handlers=handlers)

    # Act
    with pools.connect("hr"):
        pass

    # Assert
    assert (opened["startup_params"], opened["socket_timeout"]) == (startup, socket_timeout)


class _Rollback:
    def rollback(self) -> None:
        pass

    def close(self) -> None:
        pass


def _served(tmp_path: Path, answers: list[tuple[int, bytes]]):
    (tmp_path / "token").write_text("t")
    return serving(answers, "t")


@pytest.mark.parametrize(("answers", "slept"), [([(503, b""), (200, b"zip")], [1]),
                                                ([(429, b""), (500, b""), (200, b"zip")], [1, 2])])
def test_given_backend_busy_then_ok_when_fetched_then_retried_with_backoff(
        tmp_path: Path, answers: list[tuple[int, bytes]], slept: list[int]) -> None:
    # Arrange
    sleeps: list[float] = []

    with _served(tmp_path, answers) as url:
        # Act
        fetched = artifact.fetch(url, "SHA256:" + hashlib.sha256(b"zip").hexdigest().upper(), tmp_path / "token",
                                 tmp_path / "out", sleep=sleeps.append)

    # Assert
    assert fetched.read_bytes() == b"zip" and sleeps == slept


def test_given_backend_down_when_fetched_then_refused_after_every_try(tmp_path: Path) -> None:
    # Arrange
    sleeps: list[float] = []

    with _served(tmp_path, [(503, b"")]) as url:
        # Act / Assert
        with pytest.raises(ArtifactError, match=r"answered 503 \(6 tries\)"):
            artifact.fetch(url, "sha256:" + "0" * 64, tmp_path / "token", tmp_path / "out", sleep=sleeps.append)
    assert sleeps == [1, 2, 4, 8, 16]


@pytest.mark.parametrize(("env", "problem"), [
    ({"AISOLATE_CODE_DIGEST": "sha256:" + "0" * 64}, "code needs the backend"),
    ({"AISOLATE_CODE_DIGEST": "sha256:" + "0" * 64, "AISOLATE_CODE_URL": "http://127.0.0.1:1/x",
      "AISOLATE_CODE_TOKEN_FILE": "/nonexistent/token"}, "no code token at AISOLATE_CODE_TOKEN_FILE"),
])
def test_given_code_digest_without_url_or_token_when_located_then_refused(
        tmp_path: Path, env: dict[str, str], problem: str) -> None:
    # Arrange / Act / Assert
    with pytest.raises(ArtifactError, match=problem):
        artifact.locate(env, tmp_path / "fetched")


@pytest.mark.parametrize(("env", "located"), [({}, None), ({"AISOLATE_PLUGINS_DIR": "/p", "AISOLATE_PLUGIN_DIGESTS": "d"},
                                                             (Path("/p"), "d"))])
def test_given_no_code_digest_when_located_then_local_plugins_dir_or_none(env: dict[str, str], located) -> None:
    # Arrange / Act / Assert
    assert artifact.locate(env) == located


def test_given_rendered_code_env_when_located_then_fetched_into_fresh_dir(tmp_path: Path) -> None:
    # Arrange
    (tmp_path / "fetched").mkdir()
    (tmp_path / "fetched" / "stale.zip").write_text("")
    with _served(tmp_path, [(200, b"zip")]) as url:
        env = {"AISOLATE_CODE_URL": url, "AISOLATE_CODE_DIGEST": "sha256:" + hashlib.sha256(b"zip").hexdigest(),
               "AISOLATE_CODE_TOKEN_FILE": str(tmp_path / "token")}

        # Act
        located = artifact.locate(env, tmp_path / "fetched")

    # Assert
    assert located == (tmp_path / "fetched", env["AISOLATE_CODE_DIGEST"])
    assert sorted(p.name for p in (tmp_path / "fetched").iterdir()) == ["aisolate-code.zip"]


def test_given_zip_inflating_beyond_free_space_when_extracted_then_refused(
        tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    # Arrange
    zip_path = _zip(tmp_path / "a.zip", {"src/p/__init__.py": b"x" * 1000})
    monkeypatch.setattr(artifact.shutil, "disk_usage", lambda _: type("U", (), {"free": 999})())

    # Act / Assert
    with pytest.raises(ArtifactError, match="inflates to 1000 bytes, more than the 999 free"):
        artifact.extract(zip_path, tmp_path / "code")
