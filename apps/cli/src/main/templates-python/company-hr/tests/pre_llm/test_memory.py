from aisolate import AirlockRef, Principal
from support import FakeAIsolateManager, FakeExchange

from acme_hr.pre_llm.memory import SessionMemory


def test_given_four_questions_when_recalled_then_the_last_three_oldest_first_each_cut_to_200():
    # Arrange
    memory, eric = SessionMemory(), FakeAIsolateManager()
    for question in ["first", "second", "third", "x" * 250]:
        memory.remember(FakeExchange(question), None, eric)
    following = FakeExchange("and last year?")

    # Act
    memory.recall(following, eric)

    # Assert
    assert following.context == ["The user's recent questions, oldest first: second | third | " + "x" * 200]


def test_given_another_users_questions_when_recalled_then_nothing_of_theirs():
    # Arrange
    memory, eric = SessionMemory(), FakeAIsolateManager()
    memory.remember(FakeExchange("What is Nora's salary?"), None, eric)
    nora = FakeAIsolateManager(
        Principal("nora", "nora@example.com", frozenset({"ceo"})), AirlockRef("test-space", "hr", "test")
    )
    hers = FakeExchange("Average salary by department?")

    # Act
    memory.recall(hers, nora)

    # Assert
    assert hers.context == []
