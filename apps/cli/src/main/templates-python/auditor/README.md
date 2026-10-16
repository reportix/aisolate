# Halvard & Finch audit assistant

An AIsolate project: the code, the rules and the settings of the `${AIRLOCK}` airlock, the audit
assistant for one audited company. The audit firm is the space; each audited company is an
airlock of it, made from this same template: `aisolate create
-s=<space> -t=auditor --airlock=<company>` once per company. `aisolate deploy` builds the project and
deploys it, together with `aisolate.yaml`, into the space `AISOLATE_SPACE` names in `.env`.
The airlock is created or updated from the `Airlock` at the top of `aisolate.yaml` on every
deploy: its name, its description; a field the file leaves out keeps what the AIsolate UI set.

## One airlock per company, one code

The code never names a company's database. It reads the data source `ledger`, and each company's
airlock defines its own `ledger` in the AIsolate UI: the URL of that company's database and a
database role that can connect to nothing else. The three companies' airlocks are the same
code, so what makes an answer about one company is the airlock it is asked on. The auditors are
the space's users and may ask every company's airlock; an answer about one company never draws on
another's data, because that airlock's pods never hold a connection to it.

## The tree

```
aisolate.yaml                     the airlock (the company): its name, description and settings
.env, .env.example                  AISOLATE_ENDPOINT, AISOLATE_SPACE; no credentials, the airlock holds them
rules/                              one rule set per entity: the auditor reads everything, bank accounts masked
src/halvardfinch_audit/
  pre_llm/documents.py              AuditDocumentsContextPreparator: the board minutes and policies
  pre_llm/memory.py                 SessionMemory
  tools/ledger_tools.py             LedgerTools: read_records, aggregate_records,
                                    related_party_payments, invoices_without_contract
  post_llm/period.py                PeriodVerifier, a @verifier
tests/                              the tests, with tests/support's FakeAIsolateManager standing in for AIsolate
pyproject.toml, libs/aisolate-sdk/  the project's name and Python 3.13; the SDK, its one dependency
```

## The code

`LedgerTools` declares five entities of the company's books in the data source `ledger`:

| Entity | Holds |
|---|---|
| `ledger.journal_entries` | the general ledger, one row per line; `related_party` (yes or no) and `relationship` come from the related parties |
| `ledger.invoices` | the supplier invoices; `has_contract` is no when the invoice quotes no contract of that supplier |
| `ledger.contracts` | the contracts, with their counterparty and annual value |
| `ledger.related_parties` | the owners, directors and their companies (IAS 24) |
| `ledger.reported_figures` | what the company reported, by XBRL concept and period: `ifrs-full:Revenue`, `ifrs-full:ProfitLoss`, `ifrs-full:Assets`; `figure_id` is the two together |

Its tools: `read_records` (always up to 50 rows, oldest first, with no limit to ask: "the five largest" with
limit 5 read the five oldest) and `aggregate_records`, which take the
entity, and the two audit tests a small model gets wrong with free filters, one call each:
`related_party_payments` (up to 50 payments to related parties above 10,000 EUR, or the amount
asked) and `invoices_without_contract` (up to 50). A result past 50 rows is cut without saying so,
so the prompt tells the model to filter by date for the latest entries and to rank the rows read for the
largest. A tool reaches the data only through the governed query AIsolate
hands it.

`AuditDocumentsContextPreparator` searches `audit.documents` in the same data source with pgvector
and adds the closest sections to the exchange, for a user with the role `auditor` only: the rules
bind the tools, the handler binds itself. `SessionMemory` keeps each user's last three questions;
each company's airlock runs its own pods, so a question about one company is never recalled in
another's.

Grounding is AIsolate's: it writes every figure in from the row it cites, with the counterparty or
supplier, the date and the unit the entities declare, and shows a number the model typed itself as
unsourced (`docs/verification.md`). The verifier is the project's: `PeriodVerifier` checks that
every figure is for a period the question names, by year: "FY2025", "2025" and a date in 2025 are
all 2025. A question that names no year is not judged, nor are the cells a computation is made of, and
a figure for a year such a computation spans is expected.

## Rules

`rules/<entity>.json`, one per entity: the role `auditor` reads and aggregates every row; the bank
account numbers (`counterparty_iban`, `supplier_iban`, `iban`) arrive masked as `****` and cannot
be filtered on. Every other role gets nothing.

## Commands

[uv](https://docs.astral.sh/uv/) runs the project; it installs Python 3.13 when the machine has none.

- `uv run pytest` runs the tests; `uv run python -m aisolate build` writes
  `build/aisolate/halvardfinch_audit.zip`, the artifact.
- `aisolate build` builds the artifact the same way, with uv, or with Python 3.13 when the
  machine has no uv.
- `aisolate login`, then `aisolate deploy`: the airlock `${SPACE}/${AIRLOCK}`, once the space exists in the
  AIsolate UI; it answers once the airlock's data source `ledger` is set there too.
