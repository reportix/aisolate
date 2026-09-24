import json
from pathlib import Path
from typing import Any

import pytest
from conftest import case_file

from aisolate_runtime import config
from aisolate_runtime.config import ConfigurationError

CASES = case_file("llm/catalog.json")


@pytest.mark.parametrize("case", CASES["cases"], ids=[c["id"] for c in CASES["cases"]])
def test_given_documents_when_loaded_then_the_catalog_or_the_problems_of_the_case(tmp_path: Path, case: dict[str, Any]) -> None:
    # Arrange
    files = {**CASES["base"], **case["files"]}
    for name, document in files.items():
        if document is not None:
            (tmp_path / name).write_text(json.dumps(document))
    expect = case["expect"]

    # Act
    try:
        airlock = config.load(tmp_path, None).airlock("hr")
        problems = None
    except ConfigurationError as refused:
        problems = refused.problems

    # Assert
    if "problems" in expect:
        assert set(problems or ()) == set(expect["problems"])
    else:
        assert problems is None
        assert [{"id": m.id, "provider": m.provider, "url": m.url, "model": m.model,
                 **({"secretRef": m.secret_ref} if m.secret_ref else {})} for m in airlock.llms] == expect["llms"]
        assert airlock.llm == expect["main"] == airlock.main_llm.id
        assert [{"id": d.id, "url": d.url, "secretRef": d.secret_ref} for d in airlock.data_sources] == expect["data_sources"]
