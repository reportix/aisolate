from aisolate import AirlockRef, Principal
from support import FakeAIsolateManager, FakeExchange

from halvardfinch_audit.pre_llm.memory import SessionMemory


def test_given_four_questions_when_recalled_then_the_last_three_oldest_first_each_cut_to_200():
    # Arrange
    memory, hannah = SessionMemory(), FakeAIsolateManager()
    for question in ["first", "second", "third", "x" * 250]:
        memory.remember(FakeExchange(question), None, hannah)
    following = FakeExchange("and last year?")

    # Act
    memory.recall(following, hannah)

    # Assert
    assert following.context == ["The user's recent questions, oldest first: second | third | " + "x" * 200]


def test_given_another_users_questions_when_recalled_then_nothing_of_theirs():
    # Arrange
    memory, hannah = SessionMemory(), FakeAIsolateManager()
    memory.remember(FakeExchange("Which invoices have no contract?"), None, hannah)
    noah = FakeAIsolateManager(
        Principal("noah", "noah@example.com", frozenset({"auditor"})), AirlockRef("test-space", "audit", "test")
    )
    his = FakeExchange("Payments to related parties above 10,000 EUR?")

    # Act
    memory.recall(his, noah)

    # Assert
    assert his.context == []
