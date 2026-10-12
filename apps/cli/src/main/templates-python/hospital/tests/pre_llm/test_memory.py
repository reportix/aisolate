from aisolate import AirlockRef, Principal
from support import FakeAIsolateManager, FakeExchange

from aldermoor_ehr.pre_llm.memory import SessionMemory


def test_given_four_questions_when_recalled_then_the_last_three_oldest_first_each_cut_to_200():
    # Arrange
    memory, noah = SessionMemory(), FakeAIsolateManager()
    for question in ["first", "second", "third", "x" * 250]:
        memory.remember(FakeExchange(question), None, noah)
    following = FakeExchange("and last year?")

    # Act
    memory.recall(following, noah)

    # Assert
    assert following.context == ["The user's recent questions, oldest first: second | third | " + "x" * 200]


def test_given_another_users_questions_when_recalled_then_nothing_of_theirs():
    # Arrange
    memory, noah = SessionMemory(), FakeAIsolateManager()
    memory.remember(FakeExchange("What was Julian's last troponin?"), None, noah)
    grace = FakeAIsolateManager(
        Principal("grace", "grace@example.com", frozenset({"chief"})),
        AirlockRef("test-space", "cardiology", "test"),
    )
    hers = FakeExchange("Average blood pressure by age group?")

    # Act
    memory.recall(hers, grace)

    # Assert
    assert hers.context == []
