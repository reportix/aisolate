import pytest

from aisolate import Answer, Stage, Status, Verdict, context_preparator, memory, verifier
from aisolate.handlers import handler_mark


def test_given_decorated_class_when_marked_then_stage_order_and_description_recorded() -> None:
    # Arrange
    @memory(order=3, description="Remember the conversation")
    class SessionMemory:
        def recall(self, exchange, aisolate): ...

        def remember(self, exchange, answer, aisolate): ...

    # Act
    mark = handler_mark(SessionMemory)

    # Assert
    assert (mark.stage, mark.order, mark.description) == (Stage.CONVERSATION_MEMORY, 3, "Remember the conversation")


def test_given_data_sources_when_marked_then_recorded_and_none_by_default() -> None:
    # Arrange
    @context_preparator(description="Read HR documents", data_sources=["hr"])
    class HrDocuments:
        def prepare(self, exchange, aisolate): ...

    @context_preparator()
    class Today:
        def prepare(self, exchange, aisolate): ...

    # Act
    named, unnamed = handler_mark(HrDocuments), handler_mark(Today)

    # Assert
    assert (named.data_sources, unnamed.data_sources) == (("hr",), ())


@pytest.mark.parametrize("data_sources", ["hr", [""], [None]])
def test_given_data_sources_not_a_list_of_ids_when_marked_then_type_error(data_sources) -> None:
    # Arrange / Act / Assert
    with pytest.raises(TypeError, match="data_sources must be a list of data source ids"):
        context_preparator(data_sources=data_sources)


@pytest.mark.parametrize(("decorator", "missing"), [(context_preparator, "prepare"), (memory, "recall, remember"), (verifier, "verify")])
def test_given_class_without_stage_method_when_marked_then_type_error(decorator, missing: str) -> None:
    # Arrange
    class Empty: ...

    # Act / Assert
    with pytest.raises(TypeError, match=f"without {missing}"):
        decorator()(Empty)


def test_given_handler_of_one_stage_when_marked_for_another_then_type_error() -> None:
    # Arrange
    @verifier()
    class Both:
        def verify(self, answer, evidence, aisolate): ...

        def prepare(self, exchange, aisolate): ...

    # Act / Assert
    with pytest.raises(TypeError, match="more than one handler stage"):
        context_preparator()(Both)


@pytest.mark.parametrize(("factory", "status"), [(Verdict.pass_, Status.PASS), (Verdict.warn, Status.WARN), (Verdict.fail, Status.FAIL)])
def test_given_note_when_verdict_built_then_status_and_note_without_handler(factory, status: Status) -> None:
    # Arrange / Act
    verdict = factory("checked")

    # Assert
    assert verdict == Verdict(status, "checked", None)


def test_given_none_note_when_verdict_made_then_empty_note() -> None:
    # Arrange / Act / Assert
    assert Verdict(Status.PASS, None).note == ""


def test_given_text_and_verdicts_only_when_answer_made_then_question_and_grounding_are_empty() -> None:
    # Arrange / Act
    answer = Answer("hi", ())

    # Assert
    assert (answer.question, answer.grounding_json) == ("", "")


def test_given_sdk_when_database_error_imported_then_an_exception_type() -> None:
    # Arrange
    from aisolate import DatabaseError

    # Act / Assert
    assert issubclass(DatabaseError, Exception)
