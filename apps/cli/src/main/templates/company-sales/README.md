# Acme Sales

An AIsolate project: the code, the rules and the settings of the `${AIRLOCK}` airlock, the
one the founders ask about client accounts, invoices and what was agreed with a
client. `aisolate deploy` builds the project and deploys it, together with `aisolate.yaml`,
into the space `AISOLATE_SPACE` names in `.env`. The space is created in the AIsolate UI first:
it lists the users who may ask every airlock of it. The LLMs and the data sources are the
admin's, defined for all of AIsolate, on the space or on the airlock: the project names them by
id and holds no credential. The airlock is created or updated from `aisolate.yaml` on every deploy: its name,
its description and its own users; a field the file leaves out keeps what the AIsolate UI set.


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

## The stages

Every class of the project belongs to this airlock, whatever its package. The folders sort the
code by the stage of the exchange it runs in; they are a convention for reading, and AIsolate
reads the stage from the interface the class implements.

| Folder | Holds | Runs |
|---|---|---|
| `pre_llm/` | `ContextPreparator` handlers, which add context such as today's date, or search documents and add passages as evidence; `ConversationMemory` handlers, which recall and remember per user | before the model sees the question |
| `tools/` | `ToolPlugin` classes: the entities a tool may query, the data source they live in and the tools the model may call | while the model writes the answer |
| `post_llm/` | `AnswerVerifier` handlers, which judge the finished answer and annotate it, never block it: `CustomerVerifier` checks that every figure is about the customer the question names | after the model answered |

Nothing is registered anywhere: AIsolate scans the classes. A plugin or a handler may be
package-private; it needs a no-argument constructor.

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

## The one file

`aisolate.yaml` is one `Airlock` document, `${AIRLOCK}`: every deploy creates the airlock from it,
or saves each field it writes over the airlock's; a field it leaves out keeps what the AIsolate
UI set. Its head names it: `metadata.name` is the airlock's id, `${AIRLOCK}`, which names the
endpoint, `/v1/spaces/${SPACE}/airlocks/${AIRLOCK}/chat`; `spec.displayName`, `spec.description` and
`spec.access` say what the AIsolate UI shows and who may ask (below).

Four groups follow, in the order an exchange runs:

| Group | Says |
|---|---|
| `preLLM` | `contextPreparator.handlers` and `conversationMemory.handlers` |
| `llm`, `prompt`, `tools` | the id of the LLM that answers, `granite`; the `prompt`; the `tools` the model may call |
| `postLLM` | `answerVerifier.handlers` |
| `llms`, `dataSources` | optional, none here: LLMs and databases of the airlock's own, each replacing an inherited one of the same id |

Who may ask: the space lists its users, by e-mail, and the
airlock its own, `access.users`, and `access.callers`, the clients that may call (`*` for any,
the default).
A space's users may ask every airlock of the space; an airlock's own users may ask that airlock
only. Who may ask the airlock is the gate for everything
it serves: the rules filter only what the tools return, by the token's roles, and
`SalesDocumentsContextPreparator` passes every client document, board memos included, to every
user the airlock admits. That is by design: anyone on the space may ask `${AIRLOCK}`. So put on the
space only people who may read all of it, give anyone else only the users list of the airlock
they need, and keep the founders on the space or on the `${AIRLOCK}` airlock's own list. Any other
user then sees no `${AIRLOCK}` airlock in the discovery and gets a 403 at its endpoint. Handlers are
listed by class
simple name and tools by name, and both are the project's: the deploy refuses one the code does
not have, and warns about a tool of the code that `tools` leaves out, which the model cannot
call, and about a handler listed nowhere, which runs nowhere. A handler may name itself in
a short title, two to four words, with `@Description("...")` ("Read client contracts"); the
AIsolate UI shows it in the airlock's graph, and
the class name when there is none. It may name the data sources it reads, `@DataSources("clients")`, which the graph shows as its Source. `llm` names an LLM the airlock sees: its own, its space's or a global one; the
deploy refuses another, `llm: no model named granite in this airlock's space or globally`. The
code names any of them by id at run time, `aisolate.llm("nomic")`, which the deploy does not
check.

A deploy replaces the airlock's code and settings in place.

## Data sources

A data source is a PostgreSQL database by JDBC URL, named by an id of lowercase letters, digits,
`_` or `-`; with pgvector the same database holds the rows and the embeddings. The admin defines them, with their credentials, for all of AIsolate, on the space or on the
airlock, and the airlock reads them all by id: the global ones, the space's, then its own, where
the same id nearer replaces the farther one.

The code reaches a data source by its id alone, never by URL, credential or variable:

- A tool plugin names the one its entities live in: `SalesTools.dataSource()` returns
  `clients`. AIsolate opens it with the credentials it keeps, and the tools reach it through the
  governed query. The deploy refuses a plugin whose data source the airlock does not define.
- A handler asks for one: `aisolate.dataSource("clients")` returns a `javax.sql.DataSource`. An
  id the airlock does not define throws `IllegalArgumentException`, `${SPACE}/${AIRLOCK} has no data
  source clients`. The PostgreSQL driver comes with the airlock.

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

## Variables and .env

A value in `aisolate.yaml` may read a variable: `${NAME}`, or `${NAME:-fallback}` when NAME may
stay unset. A data source of the airlock's own gives its `username` and `password` this way, so no
credential is ever committed; this project has none. `aisolate deploy` reads `.env` at the project root; a variable set in the shell
wins. It refuses, before the build, a variable that is unset or empty and has no fallback, and
names it. AIsolate keeps the credentials in Kubernetes Secrets, never in a document.

`AISOLATE_ENDPOINT` is where AIsolate runs, `http://localhost:8080` when unset; anything but
`localhost` or a `*.localhost` name must be https.
`AISOLATE_SPACE` is the space the project deploys into; `aisolate create -s` writes it, and
the commands that read the project refuse to run without it. `.env.example` carries example
values; `aisolate create` copies it to `.env`, which `.gitignore` keeps out of git. In `.env` a
`#` starts a comment only at the start of a line; a `#` inside a value is part of it.

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
- `aisolate deploy` checks your session, builds, and deploys the build and `aisolate.yaml` in
  one call into the space `AISOLATE_SPACE` names. It sends the file as written, with the value
  of each variable it reads, prints the deploy's warnings with a `!`, then the airlock
  (`airlock ${SPACE}/${AIRLOCK} (created, revision 1)`, or `updated`), its endpoint, and the
  tools and handlers of its code.
