# AIsolate Audit taxonomy

AIsolate Audit taxonomy, version 1.0 (2026-10-26). What an organisation's airlocks were asked over a period, and what came of it, as an XBRL report. AIsolate writes
it from its audit trail: the audit export's XBRL format, see [docs/audit.md](../../docs/audit.md).

## The report

One report per organisation and period: one XBRL 2.1 instance, a `.xbrl` file (xBRL-XML). It holds three tables,
each row a context of its own and the row's facts:

- exchanges: one row per question put to an airlock, named by its space and airlock: who asked,
  what, what the airlock answered, the outcome, the model, its tokens and the time it took.
- tool calls: one row per tool call the airlock made for it: the tool, the data source, how many rows came back
  (never the rows themselves).
- verifiers: one row per check run on the answer before it was given, and its verdict.

The rows meet on the exchange, the question's trace id (`ExchangeAxis`). Acme's report
([examples/acme/report.xbrl](examples/acme/report.xbrl)), shortened:

| Exchange | Space | Airlock | User | Question | Answer | Outcome | Model | Duration |
|---|---|---|---|---|---|---|---|---|
| 8bed… | acme | hr | elena@acme.example | What is my gross salary? | Your gross salary is 78,000 EUR. | answered | ollama/granite4.1:8b | PT2.615S |
| 23a3… | acme | hr | mark@acme.example | What is my gross salary? | Your gross salary is 235,000 EUR. | answered | ollama/granite4.1:8b | PT3.221S |
| ae16… | acme | sales | elena@acme.example | Which invoices are overdue? | | refused | | PT0S |

| Exchange | Tool call | ToolName | DataSource | RowsReturned |
|---|---|---|---|---|
| 8bed… | 1 | read_my_records | hr | 1 |
| 23a3… | 1 | read_my_records | hr | 5 |

The answers read as the airlock renders them. The report has no verifier row here: none of these exchanges ran a
verifier. The templates ship them now, and an airlock that lists them has a row per verifier per answer:
company-sales runs `CustomerVerifier`, the auditor's `PeriodVerifier`, the hospital's `LatestReadingVerifier`,
`FormularyVerifier` and `PatientVerifier`; the contracts airlock of company-hr runs none.

Elena was refused on Sales: she holds none of its roles, so the airlock called no tool for her.

## The one check

**A refused question calls no tool** (`RefusalCallsNoTool`): an exchange whose outcome is `refused` has no tool
call row, no `ToolName` fact in a context of its exchange. A report that breaks it is not a valid AIsolate Audit
report; an XBRL processor says which exchange:

```
[ERROR] [message:RefusalCallsNoTool] A refused question calls no tool: exchange cb1f62d93dbaf72b7477878bbcc4df87 was refused but reports 1 tool call(s).
```

## Opening a report

Any XBRL processor opens the `.xbrl` file with the taxonomy from the package: register
`aisolate-audit-2026-10-26.zip` as a taxonomy package, so nothing is fetched from aisolate.ai, open
`examples/acme/report.xbrl` to see the facts, and validate it with the formulas run. A valid report logs no error
and `RefusalCallsNoTool evaluations : 1 satisfied, 0 not satisfied`.

Every run on the examples and its result: [examples/VALIDATION.md](examples/VALIDATION.md).

## Technical reference

| | |
|---|---|
| Version | 1.0, of 2026-10-26: the namespace and the file names keep the date, as XBRL taxonomies do |
| Namespace | `https://aisolate.ai/xbrl/audit/2026-10-26`, prefix `aisolate` |
| Entry point | `https://aisolate.ai/xbrl/audit/2026-10-26/aisolate-audit-2026-10-26.xsd`, the only one |
| Package | `aisolate-audit-2026-10-26.zip` (Taxonomy Package 1.0); its source is the folder `aisolate-audit-2026-10-26/` |
| Specifications | XBRL 2.1, Dimensions 1.0, Formula 1.0, Taxonomy Packages 1.0 |
| Report | one XBRL 2.1 instance (xBRL-XML), `.xbrl`, UTF-8 |
| Publisher | AIsolate |

**Entity and period.** The entity is the reporting organisation (an LEI, or a scheme and identifier); every fact
has the report's period, a duration of whole UTC days, `startDate` and `endDate` both included. When a question was
asked is a fact, `ExchangeTime`.

**Contexts.** One per row: the entity, the period and, in the scenario, the row's typed dimensions: `ExchangeAxis`
alone for an exchange, with `ToolCallAxis` for a tool call, with `VerifierAxis` for a verifier run. A value the
audit event lacks is no fact; the counts have the unit `xbrli:pure` and `decimals="INF"`.

**Concepts.** All items, period type duration, labels and one-line documentation in English.

| Concept | Type | Table | Meaning |
|---|---|---|---|
| `Space` | string | exchanges | the space of the airlock the question was put to: its id |
| `Airlock` | string |  | the airlock the question was put to: its id |
| `ExchangeTime` | dateTime |  | when the question reached the airlock, UTC |
| `User` | string |  | who asked: the e-mail, a machine client by its client id |
| `Question` | string |  | as asked |
| `Answer` | string |  | as given; absent unless answered |
| `Outcome` | string |  | `answered`, `dont_know`, `refused` or `error` |
| `Model` | string |  | `provider/model`, e.g. `ollama/granite4.1:8b`; absent on a refusal |
| `InputTokens`, `OutputTokens` | non-negative integer |  | absent when the provider reported none, not zero |
| `Duration` | duration |  | the time taken to answer, e.g. `PT3.2S` |
| `ToolName` | string | tool calls | the tool called |
| `DataSource` | string |  | the data source it read |
| `RowsReturned` | non-negative integer |  | rows it returned |
| `VerifierName` | string | verifiers | the check run on the answer |
| `VerifierVerdict` | string |  | `pass`, `warn` or `fail` |

**Axes**, all typed: `ExchangeAxis` (`ExchangeId`, `xs:string`, the trace id; the time when the airlock traced
none), `ToolCallAxis` and `VerifierAxis` (`ToolCallPosition` and `VerifierPosition`, `xs:positiveInteger`, the
position within the exchange, from 1).

**Hypercubes**, closed, dimensions in the scenario, each in its own role:

| Role | Hypercube | Axes | Concepts |
|---|---|---|---|
| Exchanges | `ExchangeHypercube` | Exchange | the eleven exchange concepts |
| ToolCalls | `ToolCallHypercube` | Exchange, Tool call | `ToolName`, `DataSource`, `RowsReturned` |
| Verifiers | `VerifierHypercube` | Exchange, Verifier | `VerifierName`, `VerifierVerdict` |

**The check** is one Formula 1.0 value assertion, `RefusalCallsNoTool`: for each `Outcome` fact equal to
`refused`, `empty($tools)`, where `$tools` is every `ToolName` fact of the same exchange (the tool call axis
covered). Its unsatisfied message is in English.

**Files.**

```
aisolate-audit-2026-10-26/
  META-INF/taxonomyPackage.xml           identifier, version 1.0, the entry point
  META-INF/catalog.xml                   https://aisolate.ai/xbrl/audit/2026-10-26/ -> the folder
  aisolate-audit-2026-10-26.xsd          concepts, axes, hypercubes, roles; references the three linkbases
  aisolate-audit-2026-10-26-lab.xml      labels and documentation
  aisolate-audit-2026-10-26-def.xml      the hypercubes
  aisolate-audit-2026-10-26-for.xml      the check
examples/
  acme/ halvard-finch/ aldermoor/        the three stage demos: audit.log (the airlocks' audit events) and report.xbrl
  broken/                                halvard-finch's report.xbrl with a tool call added to its refused exchange
  VALIDATION.md                          the validation runs and their results
```

After editing a file under `aisolate-audit-2026-10-26/`, rebuild the package:

```
cd taxonomy/audit && rm -f aisolate-audit-2026-10-26.zip && zip -qrX aisolate-audit-2026-10-26.zip aisolate-audit-2026-10-26
```

## What a report holds

Every fact comes from an audit event, and nothing else: no row, no prompt, no document. A report holds each
question and answer as asked and given, so a hospital's holds what its doctors asked about patients and what they
were told: it stays inside the organisation, read by its own auditors, as the audit trail does.

The examples hold no real person: the people are the demos' invented cast, at the demos' own fictional domains
(`acme.example`, `halvardfinch.example`, `aldermoor.example`), the companies, staff and patients are invented,
and the LEIs are invented too, with valid check digits.
