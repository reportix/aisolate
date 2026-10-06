from aisolate import AirlockRef, Principal
from support import FakeAIsolateManager, FakeExchange

from acme_sales.pre_llm.memory import SessionMemory


def test_given_four_questions_when_recalled_then_the_last_three_oldest_first_each_cut_to_200():
    # Arrange
    memory, leo = SessionMemory(), FakeAIsolateManager()
    for question in ["first", "second", "third", "x" * 250]:
        memory.remember(FakeExchange(question), None, leo)
    following = FakeExchange("and last year?")

    # Act
    memory.recall(following, leo)

    # Assert
    assert following.context == ["The user's recent questions, oldest first: second | third | " + "x" * 200]


def test_given_another_users_questions_when_recalled_then_nothing_of_theirs():
    # Arrange
    memory, leo = SessionMemory(), FakeAIsolateManager()
    memory.remember(FakeExchange("Which invoices are overdue?"), None, leo)
    nora = FakeAIsolateManager(
        Principal("nora", "nora@example.com", frozenset({"founder"})), AirlockRef("test-space", "sales", "test")
    )
    hers = FakeExchange("Total contract value by industry?")

    # Act
    memory.recall(hers, nora)

    # Assert
    assert hers.context == []
