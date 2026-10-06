from support import FakeAIsolateManager, FakeExchange

from acme_sales.pre_llm.documents import SalesDocumentsContextPreparator, to_vector


def test_given_no_data_source_clients_when_prepare_then_no_passages_and_no_error():
    # Arrange: the fake defines no data source, as an airlock without clients would
    exchange = FakeExchange("What did we agree with Varnholt on the price cap?")

    # Act
    SalesDocumentsContextPreparator().prepare(exchange, FakeAIsolateManager())

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
