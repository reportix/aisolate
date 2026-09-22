from typing import Any

import pytest

from aisolate import Column, ColumnType, Entity, GovernedQuery, Labels, Result, ToolPlugin, tool
from aisolate.arguments import schema

NUMBER = ColumnType.NUMBER
TEXT = ColumnType.TEXT


def test_given_blank_unit_when_column_made_then_unit_is_none() -> None:
    # Arrange / Act
    column = Column("salary", "c.salary", NUMBER, unit=" ", unit_column="")

    # Assert
    assert (column.unit, column.unit_column) == (None, None)


def test_given_negative_decimals_when_column_made_then_value_error() -> None:
    # Arrange / Act / Assert
    with pytest.raises(ValueError, match="decimals of column salary cannot be negative"):
        Column("salary", "c.salary", NUMBER, decimals=-1)


def test_given_number_column_when_marked_a_fact_then_value_error() -> None:
    # Arrange / Act / Assert: every typed number is checked anyway
    with pytest.raises(ValueError, match="column salary is a number: only a text column can be a fact"):
        Column("salary", "c.salary", NUMBER, fact=True)
    with pytest.raises(ValueError, match="column salary is a number"):
        Column("salary", "c.salary", NUMBER).with_fact()


def test_given_text_column_when_with_fact_then_a_fact_and_nothing_else_changed() -> None:
    # Arrange
    column = Column("drug", "m.drug", TEXT)

    # Act
    fact = column.with_fact()

    # Assert
    assert (column.fact, fact.fact) == (False, True)
    assert fact == Column("drug", "m.drug", TEXT, fact=True)


def test_given_unit_column_when_with_unit_then_unit_column_cleared() -> None:
    # Arrange
    column = Column("salary", "c.salary", NUMBER).with_unit_from("currency")

    # Act
    fixed = column.with_unit("EUR").with_decimals(2)

    # Assert
    assert (fixed.unit, fixed.unit_column, fixed.decimals) == ("EUR", None, 2)
    assert column.with_unit("EUR").with_unit_from("currency").unit is None


def test_given_no_subject_when_entity_made_then_subject_is_row_key_and_order_kept() -> None:
    # Arrange / Act
    entity = Entity("e", "t", "id", Column("id", "t.id", NUMBER), Column("name", "t.name", TEXT))

    # Assert
    assert entity.subject == "id"
    assert list(entity.columns) == ["id", "name"]


@pytest.mark.parametrize(
    ("columns", "labels", "message"),
    [
        ((Column("id", "a", NUMBER), Column("id", "b", NUMBER)), {}, "entity e: column id is declared twice"),
        ((Column("id", "a", NUMBER),), {"subject_name": "who"}, "entity e: subject name who is not one of its columns"),
        ((Column("id", "a", NUMBER),), {"period": "year"}, "entity e: period year is not one of its columns"),
        (
            (Column("id", "a", NUMBER).with_unit_from("currency"),),
            {},
            "entity e: unit of id currency is not one of its columns",
        ),
    ],
)
def test_given_inconsistent_declaration_when_entity_made_then_value_error(
    columns: tuple[Column, ...], labels: dict[str, str], message: str
) -> None:
    # Arrange / Act / Assert
    with pytest.raises(ValueError, match=f"^{message}$"):
        Entity("e", "t", "id", *columns, **labels)


def test_given_entity_when_with_labels_then_new_entity_keeps_the_rest() -> None:
    # Arrange
    entity = Entity("e", "t", "id", Column("id", "t.id", NUMBER), Column("name", "t.name", TEXT), Column("y", "t.y", NUMBER))

    # Act
    labelled = entity.with_subject_name("name").with_period("y").with_subject("name")

    # Assert
    assert (labelled.subject, labelled.subject_name, labelled.period) == ("name", "name", "y")
    assert entity.subject_name is None


def test_given_hidden_and_masked_when_queryable_columns_then_both_removed_in_declaration_order() -> None:
    # Arrange
    entity = Entity("e", "t", "a", *(Column(n, n, TEXT) for n in "abcd"))

    # Act
    visible = entity.visible_columns({"b"})
    queryable = entity.queryable_columns({"b"}, {"d"})

    # Assert
    assert visible == ["a", "c", "d"]
    assert queryable == ["a", "c"]


def test_given_unknown_name_when_column_then_value_error() -> None:
    # Arrange
    entity = Entity("e", "t", "a", Column("a", "a", TEXT))

    # Act / Assert
    with pytest.raises(ValueError, match="^Unknown column: zz$"):
        entity.column("zz")


def test_given_denied_query_when_result_empty_then_nothing_to_see() -> None:
    # Arrange / Act
    result = Result.empty("", "v1.abc")

    # Assert
    assert result == Result("", 0, (), (), "v1.abc", (), "", None)


def test_given_lists_when_result_and_labels_made_then_held_as_tuples() -> None:
    # Arrange / Act
    result = Result("e", 1, ["a"], [{"a": 1}], "v", ["r"], "h", Labels(masked=["iban"], facts=["drug"]))

    # Assert
    assert (result.columns, result.rules, result.labels.masked) == (("a",), ("r",), ("iban",))
    assert (result.labels.facts, Labels().facts) == (("drug",), ())


class _Base(ToolPlugin):
    name = "base"

    @tool("first", "First tool", schema({}, []))
    def first(self, query: GovernedQuery, arguments: dict[str, Any]) -> Result:
        return Result.empty("first", "")

    @tool("second", "Second tool", schema({}, []))
    def second(self, query: GovernedQuery, arguments: dict[str, Any]) -> Result:
        return Result.empty("second", "")


class _Child(_Base):
    def second(self, query: GovernedQuery, arguments: dict[str, Any]) -> Result:
        return Result.empty("unpublished", "")

    @tool("third", "Third tool", schema({"q": {"type": "string"}}, ["q"]))
    def third(self, query: GovernedQuery, arguments: dict[str, Any]) -> Result:
        return Result.empty(arguments["q"], "")


def test_given_plugin_with_tool_methods_when_tools_then_bound_in_definition_order() -> None:
    # Arrange
    plugin = _Child()

    # Act
    tools = plugin.tools()

    # Assert
    assert [t.name for t in tools] == ["first", "third"]
    assert tools[1].input_schema == {"type": "object", "properties": {"q": {"type": "string"}}, "required": ["q"]}
    assert tools[1].handler(None, {"q": "x"}).entity == "x"


def test_given_plugin_entities_when_entity_then_found_by_name_or_none() -> None:
    # Arrange
    entity = Entity("hr.e", "t", "a", Column("a", "a", TEXT))

    class Plugin(ToolPlugin):
        name = "p"
        entities = (entity,)

    # Act / Assert
    assert Plugin().entity("hr.e") is entity
    assert Plugin().entity("nope") is None
