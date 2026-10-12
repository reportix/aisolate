import pytest
from aisolate import AirlockRef, Principal
from support import FakeAIsolateManager, FakeExchange

from halvardfinch_audit.pre_llm.documents import AuditDocumentsContextPreparator, to_vector


@pytest.mark.parametrize("role", ["employee", "chief", "audit-trainee"])
def test_given_no_auditor_role_when_prepare_then_the_database_is_never_opened(role):
    # Arrange
    clerk = FakeAIsolateManager(
        Principal("clerk", "clerk@example.com", frozenset({role})), AirlockRef("test-space", "audit", "test")
    )
    exchange = FakeExchange("What did the board decide on the warehouse lease?")

    # Act
    AuditDocumentsContextPreparator().prepare(exchange, clerk)

    # Assert
    assert clerk.data_sources_asked == []
    assert exchange.evidence == []


def test_given_auditor_and_no_data_source_ledger_when_prepare_then_no_passages_and_no_error():
    # Arrange: the fake defines no data source, as an airlock without ledger would
    hannah = FakeAIsolateManager()
    exchange = FakeExchange("What did the board decide on the warehouse lease?")

    # Act
    AuditDocumentsContextPreparator().prepare(exchange, hannah)

    # Assert
    assert hannah.data_sources_asked == ["ledger"]
    assert exchange.evidence == []
    assert exchange.context == []


def test_given_embedding_when_to_vector_then_pgvector_literal():
    # Arrange
    embedding = [0.5, -1.0, 2.25]

    # Act
    vector = to_vector(embedding)

    # Assert
    assert vector == "[0.5,-1.0,2.25]"
