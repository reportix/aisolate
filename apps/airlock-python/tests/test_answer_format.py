import pytest

from aisolate_airlock.answer_format import Cell, parse

# Expected messages as Java's AnswerFormat.parse gives them for the same text.
NOT_IN_FORMAT = [
    ("", "it is not a JSON object"),
    ("   ", "it is not a JSON object"),
    ("hello", "it is not JSON"),
    ("{} x", "it is not JSON"),
    ("NaN", "it is not JSON"),
    (" {\"text\":\"a\"}", "it is not JSON"),
    ("[1]", "it is not a JSON object"),
    ('{"figures":[]}', "text is missing"),
    ('{"text":1}', "text is missing"),
    ('{"text":"a","figures":{}}', "figures is not a list"),
    ('{"text":"a","quotes":"x"}', "quotes is not a list"),
    ('{"text":"a","figures":[{}]}', "every figure needs an id"),
    ('{"text":"a","figures":["f1"]}', "every figure needs an id"),
]


@pytest.mark.parametrize("said,message", NOT_IN_FORMAT, ids=[m + " " + repr(s) for s, m in NOT_IN_FORMAT])
def test_given_text_not_in_the_format_when_parsed_then_says_what_is_wrong(said, message):
    # Arrange
    expected = message

    # Act
    with pytest.raises(ValueError) as refused:
        parse(said)

    # Assert
    assert str(refused.value) == expected


def test_given_fenced_block_with_values_where_strings_belong_when_parsed_then_read_as_java_reads_them():
    # Arrange
    said = (
        '```json\n{"text":"a {f1}","figures":[{"id":"f1","cell":{"evidence":"ev-1","row":1.5e-4,'
        '"column":true}}],"dont_know":"true"}\n```'
    )

    # Act
    answer = parse(said)

    # Assert
    assert answer.figures[0].cell == Cell("ev-1", "1.5E-4", "true")
    assert answer.dont_know is True
    assert answer.quotes == []
