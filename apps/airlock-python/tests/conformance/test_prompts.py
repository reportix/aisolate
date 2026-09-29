import json

import pytest

from aisolate_airlock import prompts
from conftest import conformance_dir

SHARED = json.loads((conformance_dir() / "prompts" / "airlock.json").read_text(encoding="utf-8"))


def test_given_shared_file_when_packaged_then_byte_identical():
    # Arrange
    shared = (conformance_dir() / "prompts" / "airlock.json").read_bytes()

    # Act
    packaged = (prompts.__file__.rsplit("/", 1)[0] + "/prompts.json")

    # Assert
    with open(packaged, "rb") as f:
        assert f.read() == shared


@pytest.mark.parametrize("key", sorted(SHARED), ids=sorted(SHARED))
def test_given_shared_sentence_when_read_by_the_airlock_then_equal(key):
    # Arrange
    shared = SHARED[key]

    # Act
    ours = getattr(prompts, key)

    # Assert
    assert ours == shared
