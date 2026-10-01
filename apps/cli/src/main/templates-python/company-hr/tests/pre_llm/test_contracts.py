import pytest
from aisolate import Principal
from support import FakeAIsolateManager, FakeExchange

from acme_hr.pre_llm.contracts import ContractsContextPreparator, owner_of, to_vector


@pytest.mark.parametrize("roles", [{"ceo"}, {"employee", "ceo"}])
def test_given_the_ceo_when_owner_of_then_no_owner_filter_so_every_contract(roles):
    # Arrange
    jane = Principal("jane", "jane@acme.example", frozenset(roles))

    # Act
    owner = owner_of(jane)

    # Assert
    assert owner is None


@pytest.mark.parametrize("role", ["employee", "hr"])
def test_given_anyone_else_when_owner_of_then_their_own_contract_only(role):
    # Arrange
    john = Principal("john", "john@acme.example", frozenset({role}))

    # Act
    owner = owner_of(john)

    # Assert
    assert owner == "john@acme.example"


def test_given_an_employee_without_email_when_owner_of_then_no_contract():
    # Arrange
    anonymous = Principal("someone", None, frozenset({"employee"}))

    # Act
    owner = owner_of(anonymous)

    # Assert
    assert owner == ""


def test_given_no_data_source_hr_when_prepare_then_no_passages_and_no_error():
    # Arrange: the fake defines no data source, as an airlock without hr would
    exchange = FakeExchange("What is my notice period?")

    # Act
    ContractsContextPreparator().prepare(exchange, FakeAIsolateManager())

    # Assert
    assert exchange.evidence == []
    assert exchange.context == []


def test_given_embedding_when_to_vector_then_pgvector_literal():
    # Arrange
    embedding = [0.5, -1.0, 2.25]

    # Act
    vector = to_vector(embedding)

    # Assert
    assert vector == "[0.5,-1.0,2.25]"
