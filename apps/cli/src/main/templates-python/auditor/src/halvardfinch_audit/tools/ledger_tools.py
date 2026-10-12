from typing import Any

from aisolate import Column, ColumnType, Entity, Filter, GovernedQuery, Op, Result, ToolPlugin, arguments, tool

NUMBER, TEXT = ColumnType.NUMBER, ColumnType.TEXT

# Whether a counterparty is a related party is a join, so the model filters one column.
JOURNAL_ENTRIES = Entity(
    "ledger.journal_entries",
    "ledger.journal_entries j LEFT JOIN ledger.related_parties r ON r.name = j.counterparty",
    "entry_id",
    Column("entry_id", "j.id", NUMBER),
    Column("entry_date", "j.entry_date::text", TEXT),
    Column("account", "j.account", TEXT),
    Column("description", "j.description", TEXT),
    Column("counterparty", "j.counterparty", TEXT),
    Column("counterparty_iban", "j.counterparty_iban", TEXT),
    Column("entry_type", "j.entry_type", TEXT),
    Column("amount_eur", "j.amount_eur", NUMBER, unit="EUR"),
    Column("related_party", "CASE WHEN r.id IS NULL THEN 'no' ELSE 'yes' END", TEXT),
    Column("relationship", "r.relationship", TEXT),
    subject_name="counterparty",
    period="entry_date",
)

# An invoice matches a contract of the same supplier under the reference it quotes.
INVOICES = Entity(
    "ledger.invoices",
    "ledger.invoices i LEFT JOIN ledger.contracts c ON c.reference = i.contract_ref AND c.counterparty = i.supplier",
    "invoice_id",
    Column("invoice_id", "i.id", NUMBER),
    Column("number", "i.number", TEXT),
    Column("supplier", "i.supplier", TEXT),
    Column("issued_on", "i.issued_on::text", TEXT),
    Column("amount_eur", "i.amount_eur", NUMBER, unit="EUR"),
    Column("contract_ref", "i.contract_ref", TEXT),
    Column("has_contract", "CASE WHEN c.id IS NULL THEN 'no' ELSE 'yes' END", TEXT),
    Column("supplier_iban", "i.supplier_iban", TEXT),
    Column("paid_on", "i.paid_on::text", TEXT),
    subject_name="supplier",
    period="issued_on",
)

CONTRACTS = Entity(
    "ledger.contracts",
    "ledger.contracts c",
    "contract_id",
    Column("contract_id", "c.id", NUMBER),
    Column("reference", "c.reference", TEXT),
    Column("counterparty", "c.counterparty", TEXT),
    Column("subject", "c.subject", TEXT),
    Column("start_date", "c.start_date::text", TEXT),
    Column("end_date", "c.end_date::text", TEXT),
    Column("annual_value_eur", "c.annual_value_eur", NUMBER, unit="EUR"),
    subject_name="counterparty",
)

RELATED_PARTIES = Entity(
    "ledger.related_parties",
    "ledger.related_parties r",
    "party_id",
    Column("party_id", "r.id", NUMBER),
    Column("name", "r.name", TEXT),
    Column("relationship", "r.relationship", TEXT),
    Column("since", "r.since::text", TEXT),
    Column("iban", "r.iban", TEXT),
    subject_name="name",
)

# What the company reported, by XBRL concept of the IFRS taxonomy (ifrs-full:Revenue, ...) and
# period: the two together key a figure, as in the table.
REPORTED_FIGURES = Entity(
    "ledger.reported_figures",
    "ledger.reported_figures f",
    "figure_id",
    Column("figure_id", "f.concept || ' ' || f.period", TEXT),
    Column("concept", "f.concept", TEXT),
    Column("label", "f.label", TEXT),
    Column("period", "f.period", TEXT),
    Column("value_eur", "f.value_eur", NUMBER, unit="EUR"),
    period="period",
)

ENTITIES = (JOURNAL_ENTRIES, INVOICES, CONTRACTS, RELATED_PARTIES, REPORTED_FIGURES)

NAMES = [e.name for e in ENTITIES]

# What the model reads to pick an entity: each one with its columns.
CATALOG = "; ".join(f"{e.name} ({', '.join(e.columns)})" for e in ENTITIES)

ENTITY_SCHEMA = {"type": "string", "enum": NAMES, "description": "The entity to query"}

# Rows per read: enough for a sample, never the whole ledger in a prompt.
MAX_ROWS = 50

# The threshold of the related-party payment test when the question names none.
DEFAULT_THRESHOLD_EUR = 10_000


class LedgerTools(ToolPlugin):
    """Halvard & Finch's audit tool plugin over one audited company's books: the general ledger, the
    supplier invoices, the contracts, the related parties and the reported figures, all in the data
    source ``ledger``. Which company's books that is, the airlock decides: each company's airlock
    defines its own ``ledger``, so the same code never sees two companies. The rules come from the
    project's rules/ folder; every query goes through the governed query AIsolate hands the tool."""

    name = "ledger-tools"
    entities = ENTITIES
    # The id of the data source each company's airlock defines: that company's books.
    data_source = "ledger"

    @tool(
        "read_records",
        f"Read up to {MAX_ROWS} records of one entity of the audited company's books, oldest first: "
        + CATALOG
        + ". Filters narrow them (a name with op ilike, an amount with gt, a date as ISO"
        " text with gte and lt); for the latest records filter by date. An empty result"
        " means there is nothing to see.",
        # No limit: rows come oldest first, so "the five largest" with limit 5 read the five oldest.
        arguments.schema({"entity": ENTITY_SCHEMA, "filters": arguments.FILTERS_SCHEMA}, ["entity"]),
    )
    def read_records(self, query: GovernedQuery, args: dict[str, Any]) -> Result:
        return query.read(_entity(args), arguments.filters(args.get("filters")), MAX_ROWS)

    @tool(
        "aggregate_records",
        "Aggregate records of one of the same entities: function sum, avg, count, min or max"
        " over a numeric column, optionally grouped by a column (account, supplier,"
        " counterparty, entry_type).",
        arguments.schema(
            {
                "entity": ENTITY_SCHEMA,
                "function": arguments.FUNCTION_SCHEMA,
                "column": {"type": "string", "description": "Column to aggregate"},
                "group_by": {"type": "string", "description": "Column to group by"},
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

    # The two audit tests a small model gets wrong with free filters: one call each.
    @tool(
        "related_party_payments",
        f"The audit test for payments to related parties: up to {MAX_ROWS}"
        " payments in the general ledger, in entry order, to a related party above a"
        f" threshold in EUR, {DEFAULT_THRESHOLD_EUR} unless the question names another.",
        arguments.schema(
            {
                "min_amount_eur": {
                    "type": "number",
                    "description": f"Only payments above this amount; default {DEFAULT_THRESHOLD_EUR}",
                }
            },
            [],
        ),
    )
    def related_party_payments(self, query: GovernedQuery, args: dict[str, Any]) -> Result:
        return query.read(JOURNAL_ENTRIES.name, related_party_payment_filters(args.get("min_amount_eur")), MAX_ROWS)

    @tool(
        "invoices_without_contract",
        f"The audit test for invoices without a matching contract: up to {MAX_ROWS}"
        " supplier invoices, in invoice order, that quote no contract, or one that is not"
        " a contract with that supplier.",
        arguments.schema({}, []),
    )
    def invoices_without_contract(self, query: GovernedQuery, args: dict[str, Any]) -> Result:
        return query.read(INVOICES.name, [Filter("has_contract", Op.EQ, "no")], MAX_ROWS)


def related_party_payment_filters(min_amount: Any) -> list[Filter]:
    """The tool server checks the threshold is a number, as for any filter on a numeric column."""
    threshold = DEFAULT_THRESHOLD_EUR if min_amount is None else min_amount
    return [
        Filter("related_party", Op.EQ, "yes"),
        Filter("entry_type", Op.EQ, "payment"),
        Filter("amount_eur", Op.GT, threshold),
    ]


# The governed query would answer an entity of another plugin with zero rows; a message names the
# choice the model has.
def _entity(args: dict[str, Any]) -> str:
    name = arguments.string(args.get("entity"), "entity")
    if name not in NAMES:
        raise ValueError(f"unknown entity {name}; one of [{', '.join(NAMES)}]")
    return name
