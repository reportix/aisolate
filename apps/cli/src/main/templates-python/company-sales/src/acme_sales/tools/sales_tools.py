from typing import Any

from aisolate import Column, ColumnType, Entity, GovernedQuery, Result, ToolPlugin, arguments, tool

NUMBER, TEXT = ColumnType.NUMBER, ColumnType.TEXT

# Dates are text in ISO form: the model filters them with gte and lt as it would numbers.
ACCOUNTS = Entity(
    "clients.accounts",
    "clients.accounts a",
    "account_id",
    Column("account_id", "a.id", NUMBER),
    Column("name", "a.name", TEXT),
    Column("industry", "a.industry", TEXT),
    Column("country", "a.country", TEXT),
    Column("product", "a.product", TEXT),
    Column("account_owner", "a.account_owner", TEXT),
    Column("contract_value_eur", "a.contract_value_eur", NUMBER, unit="EUR"),
    Column("contract_start", "a.contract_start::text", TEXT),
    Column("renewal_date", "a.renewal_date::text", TEXT),
    Column("status", "a.status", TEXT),
    Column("notes", "a.notes", TEXT),
    subject_name="name",
)

# The invoice's status is derived from its dates, so the model filters on paid, overdue or open.
INVOICES = Entity(
    "clients.invoices",
    "clients.invoices i JOIN clients.accounts a ON a.id = i.account_id",
    "invoice_id",
    Column("invoice_id", "i.id", NUMBER),
    Column("client", "a.name", TEXT),
    Column("number", "i.number", TEXT),
    Column("amount_eur", "i.amount_eur", NUMBER, unit="EUR"),
    Column("issued_on", "i.issued_on::text", TEXT),
    Column("due_on", "i.due_on::text", TEXT),
    Column("paid_on", "i.paid_on::text", TEXT),
    Column(
        "status",
        "CASE WHEN i.paid_on IS NOT NULL THEN 'paid' WHEN i.due_on < current_date THEN 'overdue' ELSE 'open' END",
        TEXT,
    ),
    subject_name="client",
    period="issued_on",
)

ENTITIES = (ACCOUNTS, INVOICES)

NAMES = [e.name for e in ENTITIES]

# What the model reads to pick an entity: each one with its columns.
CATALOG = "; ".join(f"{e.name} ({', '.join(e.columns)})" for e in ENTITIES)

ENTITY_SCHEMA = {"type": "string", "enum": NAMES, "description": "The entity to query"}


class SalesTools(ToolPlugin):
    """Acme's sales tool plugin: the client accounts and their invoices, and the two tools it
    publishes on AIsolate's tool server, each taking the entity it reads. No identity join: the
    founders read every row, so no row is anyone's own. The rules come from the project's rules/
    folder, never from here; every query goes through the governed query AIsolate hands the tool."""

    name = "sales-tools"
    entities = ENTITIES
    # The id of the data source that holds the clients schema, which the admin defines.
    data_source = "clients"

    @tool(
        "read_records",
        "Read up to 20 records of one entity: "
        + CATALOG
        + ". Dates are ISO text (YYYY-MM-DD). Pass the entity and, only when the question"
        " names a value, filters on it; a read with no filters returns every row the"
        " asking user may see. An empty result means there is nothing this user may see.",
        arguments.schema(
            {
                "entity": ENTITY_SCHEMA,
                "filters": arguments.FILTERS_SCHEMA,
                "limit": {"type": "integer", "description": "Maximum rows, at most 20"},
            },
            ["entity"],
        ),
    )
    def read_records(self, query: GovernedQuery, args: dict[str, Any]) -> Result:
        return query.read(
            _entity(args),
            arguments.filters(args.get("filters")),
            # At most 20 rows: the assistant never pastes every client into a prompt.
            min(arguments.limit(args.get("limit"), 20), 20),
        )

    @tool(
        "aggregate_records",
        "Aggregate records of one entity: "
        + CATALOG
        + ". Function sum, avg, count, min or max over a numeric column, optionally"
        " grouped by a column.",
        arguments.schema(
            {
                "entity": ENTITY_SCHEMA,
                "function": arguments.FUNCTION_SCHEMA,
                "column": {"type": "string", "description": "Column to aggregate"},
                "group_by": {
                    "type": "string",
                    "description": "Column to group by: one row per value, for example department or"
                    " industry when asked per department or per industry",
                },
                "filters": arguments.FILTERS_SCHEMA,
            },
            ["entity", "function", "column"],
        ),
    )
    def aggregate_records(self, query: GovernedQuery, args: dict[str, Any]) -> Result:
        return query.aggregate(
            _entity(args),
            arguments.function(args.get("function")),
            arguments.string(args.get("column"), "column"),
            None if args.get("group_by") is None else arguments.string(args.get("group_by"), "group_by"),
            arguments.filters(args.get("filters")),
        )


# The governed query would answer an entity of another plugin with zero rows; a message names the
# choice the model has.
def _entity(args: dict[str, Any]) -> str:
    name = arguments.string(args.get("entity"), "entity")
    if name not in NAMES:
        raise ValueError(f"unknown entity {name}; one of [{', '.join(NAMES)}]")
    return name
