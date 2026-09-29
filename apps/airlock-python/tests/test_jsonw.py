import pytest

from aisolate_airlock import jsonw


# Each expected text is Java's Double.toString of the value (JDK 25), what the Java airlock's tool_call and audit
# line carry for a model's argument.
@pytest.mark.parametrize(
    ("value", "java"),
    [(1e7, "1.0E7"), (-1e7, "-1.0E7"), (12345678.9, "1.23456789E7"), (9999999.999, "9999999.999"), (0.001, "0.001"),
     (9.99e-4, "9.99E-4"), (0.0001, "1.0E-4"), (-1.5e-4, "-1.5E-4"), (1e16, "1.0E16"), (1e22, "1.0E22"),
     (5e-324, "4.9E-324"), (0.1, "0.1"), (100.0, "100.0"), (-0.0, "-0.0"), (0.0, "0.0")],
)
def test_given_a_double_when_dumped_then_as_java_double_to_string(value: float, java: str) -> None:
    # Arrange
    arguments = {"limit": value}

    # Act
    text = jsonw.dumps(arguments)

    # Assert
    assert text == '{"limit":' + java + "}"
