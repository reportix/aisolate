"""Each Python template deploys the same airlock as its Java twin: the same aisolate.yaml, the same
rule sets, the same .env.example. Lives beside the templates, not in one: a created project has no
Java twin to compare with."""

from pathlib import Path

import pytest

PYTHON = Path(__file__).parent
JAVA = PYTHON.parent / "templates"
TEMPLATES = ["company-hr", "company-sales", "auditor", "hospital"]

# What only reads differently in Python: the API a comment names.
PYTHON_NAMES = {
    "# dataSource(), a handler": "# data_source, a handler",
    "aisolate.dataSource(id)": "aisolate.data_source(id)",
}


def test_given_the_template_folders_when_listed_then_each_language_has_the_same_templates():
    # Arrange
    def templates(root):
        return sorted(p.name for p in root.iterdir() if p.is_dir() and not p.name.startswith((".", "_")))

    # Act
    python, java = templates(PYTHON), templates(JAVA)

    # Assert
    assert python == java == sorted(TEMPLATES)


@pytest.mark.parametrize("template", TEMPLATES)
def test_given_a_template_when_aisolate_yaml_compared_then_javas_with_the_python_api_names(template):
    # Arrange
    java = (JAVA / template / "aisolate.yaml").read_text()

    # Act
    for java_name, python_name in PYTHON_NAMES.items():
        java = java.replace(java_name, python_name)

    # Assert
    assert (PYTHON / template / "aisolate.yaml").read_text() == java


@pytest.mark.parametrize("template", TEMPLATES)
def test_given_a_template_when_rules_and_env_example_compared_then_byte_identical_to_java(template):
    # Arrange
    def files(root):
        # glob: a template without rules has no rules/
        paths = [*sorted((root / "rules").glob("*")), root / ".env.example"]
        return {p.relative_to(root).as_posix(): p.read_bytes() for p in paths}

    # Act
    python, java = files(PYTHON / template), files(JAVA / template)

    # Assert
    assert python == java
