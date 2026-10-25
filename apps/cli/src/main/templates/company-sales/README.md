# Acme Sales

An AIsolate project: the code, the rules and the settings of the `${AIRLOCK}` airlock, the one the
founders ask about client accounts, invoices and what was agreed with a client. `aisolate deploy`
builds the project and deploys it, together with `aisolate.yaml`, into the space `AISOLATE_SPACE`
names in `.env`. The space comes first, from the AIsolate UI, with the roles that may ask; the LLMs
and the data sources are the admin's, and the project names them by id. The full walk-through is
`docs/develop.md` in the AIsolate repository.

## The tree

```
aisolate.yaml                     the airlock: its name, description and settings
.env, .env.example                  AISOLATE_ENDPOINT, AISOLATE_SPACE; .env is never committed
rules/
  clients.accounts.json             the rule set of the clients.accounts entity
  clients.invoices.json             the rule set of the clients.invoices entity
src/main/java/com/acme/sales/     the code, by stage
  pre_llm/TodayContext.java                         ContextPreparator
  pre_llm/SalesDocumentsContextPreparator.java     ContextPreparator
  pre_llm/SessionMemory.java                        ConversationMemory
  tools/SalesTools.java                           ToolPlugin: read_records, aggregate_records
  post_llm/CustomerVerifier.java                    AnswerVerifier
src/test/java/com/acme/sales/     the tests, with FakeAIsolateManager standing in for AIsolate
build.gradle.kts                    copies rules/ to aisolate/rules/ in the build
settings.gradle.kts                 the project's name: the template, company-sales
libs/aisolate-sdk.jar             the AIsolate SDK, the one thing the project compiles against
gradle/, gradlew, gradlew.bat       the Gradle wrapper; it provisions JDK 25 when the machine has none
```

## The code

The template's classes: `SalesTools` declares two entities in the data source `clients`,
`clients.accounts` and `clients.invoices` (whose `status` is derived from its dates: paid,
overdue or open), each with the columns a tool may query, and the tools `read_records` (at most 20
rows) and `aggregate_records`, which take the entity they read; it declares no identity join,
because no row is anyone's own. A tool reaches the data only through the governed query AIsolate
hands it and holds no connection and no rules. `TodayContext` runs first and tells the model
today's date, which renewals and overdue invoices need; it adds no evidence.
`SalesDocumentsContextPreparator` searches the
client contracts and the board memo in the same data source and adds the closest sections to the
exchange as evidence. `SessionMemory` keeps each user's last three questions (200 characters each)
while the airlock's process runs.
Grounding is AIsolate's: it writes every figure in from the evidence, flags every typed number
(`docs/verification.md`) and shows each figure with the labels the entities declare: whose row
(`withSubjectName("client")`), which period (`withPeriod("issued_on")`) and the unit (`withUnit("EUR")`).
The verifier is the project's: `CustomerVerifier` checks that every figure is about the customer the question names.

## Data sources

The template's one data source, `clients`, which the admin defines, is the `clients` schema of the company's database,
opened as a role that reads the three tables and writes nothing but the vectors of
`clients.documents`; the role of the `hr` airlock cannot read this schema at all.

## Rules

A rule set is `rules/<entity>.json`, one per entity, and governs the entity of that name: which
roles may read or aggregate it, the row filter, the columns to mask or hide and the smallest group
an aggregate may return. `clients.accounts.json` and `clients.invoices.json` each hold one rule,
`founders`: the role `founder` reads and aggregates every row, nothing masked, no minimum group.
Any other role gets zero rows from the tools, whatever it asks; the rules do not filter the
client documents. The build copies `rules/` to `aisolate/rules/`.
A rule set has `entity`, `classification` and `rules`, nothing else.

## The client documents

`SalesDocumentsContextPreparator` reads the `clients.documents` table of the data source
`clients`, PostgreSQL with pgvector, through `aisolate.dataSource("clients")`. It embeds, with the LLM `nomic`
(`aisolate.llm("nomic")`), the sections that have no embedding, then the question, and adds the closest sections: the
master agreements (pricing, service levels, term and termination) and the board memo. It filters
nothing: every user the airlock admits reads every client document, so only the founders may
ask the airlock. When the airlock sees no data source `clients` or no model `nomic` that embeds, or the database does not
answer within a few seconds, the handler logs one line, adds no passages and the model says it does not know.

## Commands

- `./gradlew build` compiles, runs the tests and writes `build/libs/company-sales.jar`.
- `./gradlew test` runs the tests only.
- `aisolate login` signs you in through your browser, once; the session lasts while you use it.
- `aisolate build` builds the project with its Gradle wrapper.
- `aisolate deploy` builds and deploys the code and `aisolate.yaml` into the space `AISOLATE_SPACE`
  names, then prints the airlock, `${SPACE}/${AIRLOCK}`, and its endpoint.
