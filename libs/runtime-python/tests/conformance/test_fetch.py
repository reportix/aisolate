import hashlib
from pathlib import Path

import pytest
from conftest import case_file, serving

from aisolate_runtime import artifact
from aisolate_runtime.artifact import ArtifactError

SHARED = case_file("delivery/fetch.json")


def _digest(text: str) -> str:
    return "sha256:" + hashlib.sha256(text.encode()).hexdigest()


@pytest.mark.parametrize("case", SHARED["cases"], ids=[c["id"] for c in SHARED["cases"]])
def test_given_backend_answer_when_fetched_then_loaded_or_refused_as_shared(tmp_path: Path, case: dict) -> None:
    # Arrange
    token_file = tmp_path / "token"
    token_file.write_text(SHARED["token"] + "\n")
    dest = tmp_path / "fetched"

    with serving([(case["status"], case["served"].encode())], SHARED["token"]) as url:
        # Act
        try:
            fetched = artifact.fetch(url, _digest(SHARED["artifact"]), token_file, dest, sleep=lambda _: None)
            error = None
        except ArtifactError as refused:
            fetched, error = None, str(refused)

    # Assert
    if case["expect"]["loaded"]:
        assert error is None and fetched.read_text() == SHARED["artifact"]
    else:
        assert error is not None and case["expect"]["error"] in error
        assert not any(dest.iterdir())
