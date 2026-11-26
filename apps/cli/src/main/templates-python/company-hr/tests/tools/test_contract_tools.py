import pytest
from aisolate import Filter, Op
from support import RecordingQuery

from acme_hr.tools.contract_tools import SALARY, TERMS, ContractTools


def tool(name):
    return next(t for t in ContractTools().tools() if t.name == name)


def test_given_no_arguments_when_read_contract_terms_then_every_row_the_user_may_see():
    # Arrange
    query = RecordingQuery()

    # Act
    tool("read_contract_terms").handler(query, {})

    # Assert
    assert query.calls == [("read", "hr.contract_terms", [], 50)]


def test_given_filters_when_read_contract_terms_then_passed_on_as_they_are():
    # Arrange
    query = RecordingQuery()
    args = {"filters": [{"column": "non_compete", "op": "eq", "value": "yes"}]}

    # Act
    tool("read_contract_terms").handler(query, args)

    # Assert
    assert query.calls == [("read", "hr.contract_terms", [Filter("non_compete", Op.EQ, "yes")], 50)]


@pytest.mark.parametrize(
    "args, filters",
    [({}, []), ({"filters": [{"column": "employee", "op": "ilike", "value": "John"}]}, [Filter("employee", Op.ILIKE, "John")])],
)
def test_given_arguments_when_fetch_salary_then_reads_the_salary_entity(args, filters):
    # Arrange
    query = RecordingQuery()

    # Act
    tool("fetch_salary").handler(query, args)

    # Assert
    assert query.calls == [("read", "hr.salary", filters, 50)]
    assert set(SALARY.columns) == {"employee", "email", "salary_eur"}


def test_given_the_plugin_when_the_subject_is_its_identity_then_the_asking_users_row_is_marked_self():
    # Arrange: AIsolate marks a row self only when the entity's subject is the plugin's identity attribute
    plugin = ContractTools()

    # Act
    subject, identity = TERMS.subject, plugin.identity_attribute

    # Assert
    assert subject == identity == "email"
    assert TERMS.subject_name == "employee"
