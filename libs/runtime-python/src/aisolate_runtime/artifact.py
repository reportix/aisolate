"""The project artifact a Python pod loads."""

from __future__ import annotations

import hashlib
import http.client
import json
import platform
import shutil
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from collections.abc import Callable, Mapping
from pathlib import Path, PurePosixPath
from typing import Any, Final

from aisolate.build import MANIFEST, MANIFEST_SCHEMA, Catalog, check, discover, linux_arch

CODE_DIR: Final = Path("/tmp/aisolate/code")
FETCHED_DIR: Final = Path(tempfile.gettempdir()) / "aisolate" / "fetched"
FETCHED_NAME: Final = "aisolate-code.zip"
TRIES: Final = 6
CHUNK: Final = 1 << 20
HEADLESS: Final = ("the airlock has code (AISOLATE_CODE_DIGEST) but no AISOLATE_CODE_URL to fetch it from: "
                   "code needs the backend (headless=false)")


class ArtifactError(Exception):
    """The artifact cannot be served; the pod exits 1 with this one line."""


def load(plugins_dir: str | Path, digests: str | None, code_dir: Path = CODE_DIR) -> tuple[Catalog, dict[str, Any], Path]:
    """The one ``*.zip`` in ``plugins_dir``, pinned by ``digests`` (``AISOLATE_PLUGIN_DIGESTS``,
    comma-separated, case ignored), extracted safely to ``code_dir``, its manifest checked
    (language, schema, Python minor), ``vendor``/``src`` appended to ``sys.path``, the package
    discovered and checked against the manifest. Returns ``(catalog, manifest, code_dir)``."""
    extract(pinned(plugins_dir, digests), code_dir)
    try:
        manifest = json.loads((code_dir / MANIFEST).read_text())
    except (OSError, ValueError) as unreadable:
        raise ArtifactError(f"the artifact has no readable {MANIFEST}: {type(unreadable).__name__}") from unreadable
    python = f"{sys.version_info.major}.{sys.version_info.minor}"
    if manifest.get("language") != "python" or manifest.get("schema") != MANIFEST_SCHEMA:
        raise ArtifactError(f"the artifact is no Python artifact of schema {MANIFEST_SCHEMA}")
    if manifest.get("python") != python:
        raise ArtifactError(f"the artifact was built on Python {manifest.get('python')}, this runtime is {python}")
    node = node_arch()
    if manifest.get("arch") != node:
        raise ArtifactError(f"the artifact was built for {manifest.get('arch')}, this node is {node}: rebuild with --arch {node}")
    # Appended, not prepended: the runtime's own pinned packages, aisolate included, win.
    for path in (code_dir / "vendor", code_dir / "src"):
        if str(path) not in sys.path:
            sys.path.append(str(path))
    try:
        catalog = discover(manifest["package"])
    except Exception as broken:
        raise ArtifactError(f"the package {manifest.get('package')} does not load: {broken}") from broken
    problems = check(catalog, manifest)
    if problems:
        raise ArtifactError("the code differs from its manifest: " + "; ".join(problems))
    return catalog, manifest, code_dir


def locate(env: Mapping[str, str], fetched_dir: Path = FETCHED_DIR) -> tuple[Path, str] | None:
    """The directory holding the airlock's zip and the digest pinning it: fetched from the backend when the
    controller rendered ``AISOLATE_CODE_DIGEST``, else ``AISOLATE_PLUGINS_DIR``/``AISOLATE_PLUGIN_DIGESTS``
    (local runs and tests, nothing renders them); None without code."""
    digest = (env.get("AISOLATE_CODE_DIGEST") or "").strip()
    if digest:
        url = (env.get("AISOLATE_CODE_URL") or "").strip()
        if not url:
            raise ArtifactError(HEADLESS)
        shutil.rmtree(fetched_dir, ignore_errors=True)
        return fetch(url, digest, env.get("AISOLATE_CODE_TOKEN_FILE") or "", fetched_dir).parent, digest
    plugins_dir = (env.get("AISOLATE_PLUGINS_DIR") or "").strip()
    return (Path(plugins_dir), env.get("AISOLATE_PLUGIN_DIGESTS") or "") if plugins_dir else None


def fetch(url: str, digest: str, token_file: str | Path, dest_dir: str | Path,
          sleep: Callable[[float], Any] = time.sleep) -> Path:
    """``GET url`` with the airlock's code token as bearer, streamed to ``dest_dir/aisolate-code.zip`` and
    hashed on the way; refused (ArtifactError, no file left) on a 4xx at once, on a connection failure or a
    5xx after ``TRIES`` attempts, and on bytes whose sha256 is not ``digest``."""
    try:
        token = Path(token_file).read_text().strip()
    except OSError as missing:
        raise ArtifactError(f"no code token at AISOLATE_CODE_TOKEN_FILE {str(token_file)!r}: {missing}") from missing
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    target = Path(dest_dir) / FETCHED_NAME
    target.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(TRIES):
        hasher = hashlib.sha256()
        try:
            with urllib.request.urlopen(request, timeout=60) as response, target.open("wb") as out:
                while chunk := response.read(CHUNK):
                    hasher.update(chunk)
                    out.write(chunk)
            break
        except urllib.error.HTTPError as answered:
            answered.close()
            # A 429 or a 5xx is the backend restarting or busy; any other answer stays the same on a retry.
            if answered.code != 429 and answered.code < 500:
                target.unlink(missing_ok=True)
                raise ArtifactError(f"fetching the code from {url} answered {answered.code}") from None
            problem = f"answered {answered.code}"
        except (OSError, http.client.HTTPException) as failed:
            problem = f"failed: {failed}"
        target.unlink(missing_ok=True)
        if attempt == TRIES - 1:
            raise ArtifactError(f"fetching the code from {url} {problem} ({TRIES} tries)")
        sleep(2 ** attempt)
    fetched = "sha256:" + hasher.hexdigest()
    if fetched != digest.strip().lower():
        target.unlink()
        raise ArtifactError(f"the code fetched from {url} has digest {fetched}, not AISOLATE_CODE_DIGEST {digest}")
    return target


def node_arch() -> str:
    """This node's CPU as a manifest names it (``x86_64``, ``aarch64``), else as the kernel does."""
    try:
        return linux_arch(platform.machine())
    except ValueError:
        return platform.machine()


def pinned(plugins_dir: str | Path, digests: str | None) -> Path:
    """The one zip, refused (ArtifactError) when its ``sha256:<hex>`` is not one of ``digests``:
    ``plugin artifact <f> has digest <d> which is not in AISOLATE_PLUGIN_DIGESTS``."""
    zips = sorted(Path(plugins_dir).glob("*.zip"))
    if len(zips) != 1:
        raise ArtifactError(f"{plugins_dir} must hold exactly one .zip artifact, it holds {len(zips)}")
    pins = {d.strip().lower() for d in (digests or "").split(",") if d.strip()}
    with zips[0].open("rb") as f:
        digest = "sha256:" + hashlib.file_digest(f, "sha256").hexdigest()
    if digest not in pins:
        raise ArtifactError(f"plugin artifact {zips[0].name} has digest {digest} which is not in AISOLATE_PLUGIN_DIGESTS")
    return zips[0]


def extract(zip_path: Path, code_dir: Path) -> None:
    """Every entry under ``code_dir``, streamed; a symlink, an absolute path or ``..`` refuses the artifact,
    and so does one inflating beyond the space free there (no product limit, but no half-written code)."""
    shutil.rmtree(code_dir, ignore_errors=True)
    code_dir.mkdir(parents=True)
    try:
        with zipfile.ZipFile(zip_path) as archive:
            # zipfile stops each entry at its declared size, so the sum bounds what lands on disk.
            total = sum(info.file_size for info in archive.infolist())
            free = shutil.disk_usage(code_dir).free
            if total > free:
                raise ArtifactError(f"the artifact inflates to {total} bytes, more than the {free} free in {code_dir}")
            for info in archive.infolist():
                path = PurePosixPath(info.filename)
                if path.is_absolute() or ".." in path.parts or "\\" in info.filename or ":" in path.parts[0]:
                    raise ArtifactError(f"artifact entry {info.filename} leaves the artifact")
                if stat.S_ISLNK(info.external_attr >> 16):
                    raise ArtifactError(f"artifact entry {info.filename} is a symlink")
                if info.is_dir():
                    continue
                target = code_dir / path
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as source, target.open("wb") as out:
                    shutil.copyfileobj(source, out, CHUNK)
    except zipfile.BadZipFile as broken:
        raise ArtifactError(f"{zip_path.name} is no zip") from broken
