import pytest
from aisolate import Stage
from aisolate_runtime import config

from aisolate_airlock.__main__ import airlock_of, seconds, size


@pytest.mark.parametrize(("text", "expected"), [("8m", 480), ("90s", 90), ("500ms", 0.5), ("1h", 3600),
                                                ("PT8M", 480), ("PT1M30S", 90), ("1500", 1.5)])
def test_given_spring_duration_when_read_then_seconds(text, expected):
    # Act
    read = seconds(text)

    # Assert
    assert read == expected


@pytest.mark.parametrize(("text", "expected"), [("64MB", 64 << 20), ("1KB", 1024), ("100", 100), ("2gb", 2 << 30)])
def test_given_spring_data_size_when_read_then_bytes(text, expected):
    # Act
    read = size(text)

    # Assert
    assert read == expected


def _served() -> config.Airlock:
    return config.Airlock(
        name="hr", space="acme", description=None, prompt="Answer HR questions.",
        tools=("read_my_records",), handlers={Stage.CONTEXT_PREPARATOR: ("Docs",), Stage.CONVERSATION_MEMORY: (),
                                              Stage.ANSWER_VERIFIER: ("Check",)},
        llm="granite", llms=(config.Llm("granite", "ollama", "http://ollama:11434", "granite4.1:8b"),
                             config.Llm("nomic", "ollama", "http://ollama:11434", "nomic-embed-text")),
        data_sources=(config.DataSource("hr", "jdbc:postgresql://db/hr", "s"),),
        access=config.Access(("employee",), ("acme-chat",), ("knime",)),
        code=config.Code("sha256:" + "0" * 64, ("read_my_records",), "python"))


def test_given_served_airlock_when_mapped_then_the_airlock_definition_of_java():
    # Arrange
    configuration = config.Configuration("7b2280eab4b5", {"hr": _served()})

    # Act
    airlock = airlock_of(configuration, "hr")

    # Assert
    assert (airlock.space, airlock.id, airlock.revision, airlock.model) == ("acme", "hr", "7b2280eab4b5", "ollama")
    assert (airlock.context_preparators, airlock.memories, airlock.verifiers) == (("Docs",), (), ("Check",))
    assert (airlock.data_sources, airlock.roles, airlock.callers, airlock.clients) == (
        ("hr",), ("employee",), ("acme-chat",), ("knime",))


def test_given_unserved_airlock_when_mapped_then_refused_naming_it():
    # Arrange
    configuration = config.Configuration("7b2280eab4b5", {"hr": _served()})

    # Act / Assert
    with pytest.raises(ValueError, match="^AISOLATE_AIRLOCK sales is no served airlock$"):
        airlock_of(configuration, "sales")
