from typing import Any

from aisolate import Column, ColumnType, Entity, GovernedQuery, Result, ToolPlugin, arguments, tool

NUMBER, TEXT = ColumnType.NUMBER, ColumnType.TEXT

# The terms of every employment contract as the HR system keeps them, one row per employee: what the
# signed contract in hr.documents states, transcribed at signing and kept current by its amendments.
# The e-mail is the subject and the plugin's identity, so the asking user's row is marked self; the
# rules hide it, as the contracts' bodies carry names and never addresses.
TERMS = Entity(
    "hr.contract_terms",
    "hr.contract_terms t",
    "employee",
    Column("employee", "t.employee", TEXT),
    Column("email", "t.email", TEXT),
    Column("position", "t.position", TEXT),
    Column("department", "t.department", TEXT),
    Column("start_date", "t.start_date::text", TEXT),
    Column("salary_eur", "t.salary_eur", NUMBER, unit="EUR"),
    Column("bonus_eur", "t.bonus_eur", NUMBER, unit="EUR"),
    Column("weekly_hours", "t.weekly_hours", NUMBER, unit="hours"),
    Column("vacation_days", "t.vacation_days", NUMBER, unit="days"),
    Column("probation_months", "t.probation_months", NUMBER, unit="months"),
    Column("notice_months", "t.notice_months", NUMBER, unit="months"),
    Column("non_compete", "t.non_compete", TEXT),
    subject="email",
    subject_name="employee",
)

# What the model reads to pick a column.
COLUMNS = ", ".join(TERMS.columns)

# The same table, the salary alone: the rules in rules/hr.salary.json govern it like the terms.
SALARY = Entity(
    "hr.salary",
    "hr.contract_terms t",
    "employee",
    Column("employee", "t.employee", TEXT),
    Column("email", "t.email", TEXT),
    Column("salary_eur", "t.salary_eur", NUMBER, unit="EUR"),
    subject="email",
    subject_name="employee",
)


class ContractTools(ToolPlugin):
    """Acme's contracts tool plugin: the terms of the employment contracts as the HR system keeps
    them, and the two tools it publishes on AIsolate's tool server. Who reads which rows is the rules'
    word, from the project's rules/ folder, never from here: an employee their own row, the CEO
    everyone's; every query goes through the governed query AIsolate hands the tool."""

    name = "contract-tools"
    entities = (TERMS, SALARY)
    # The id of the data source that holds the hr schema, which the admin defines.
    data_source = "hr"
    # The token's e-mail, as principal.email, is the row's subject: AIsolate marks the asking user's
    # own row self, and the rules' row filter compares the column with it. No join: the claim is it.
    identity_attribute = "email"

    @tool(
        "read_contract_terms",
        "Read the terms of the employment contracts as the HR system keeps them, one row per employee: "
        + COLUMNS
        + ". Dates are ISO text (YYYY-MM-DD); non_compete is yes or no. A read with no filters"
        " returns every row the asking user may see, their own marked self: a question about my"
        " contract passes none. Filter by employee (ilike, the name) when the question names a"
        " person, by a term's column only when it names that term's value. An empty result means"
        " there is nothing this user may see.",
        arguments.schema({"filters": arguments.FILTERS_SCHEMA}, []),
    )
    def read_contract_terms(self, query: GovernedQuery, args: dict[str, Any]) -> Result:
        # At most 50 rows: the whole company, and the assistant never pastes more into a prompt.
        return query.read(TERMS.name, arguments.filters(args.get("filters")), 50)

    @tool(
        "fetch_salary",
        "The gross annual salary. The asking user's own: call with no filters, their row is marked self."
        " A named person's: filters on employee (ilike). Columns: employee, salary_eur.",
        arguments.schema({"filters": arguments.FILTERS_SCHEMA}, []),
    )
    def fetch_salary(self, query: GovernedQuery, args: dict[str, Any]) -> Result:
        return query.read(SALARY.name, arguments.filters(args.get("filters")), 50)
