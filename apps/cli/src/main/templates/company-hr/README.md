# Acme Contracts

An AIsolate project: the code and the settings of the `${AIRLOCK}` airlock, the one the
company's people ask about their employment contract: position, pay, working time,
notice and confidentiality. The CEO reads every contract, anyone else only their own.
`aisolate deploy` builds the project and deploys it, together with `aisolate.yaml`, into the
space `AISOLATE_SPACE` names in `.env`. The space is created in the AIsolate UI first: it lists
the users who may ask every airlock of it. The LLMs and the data sources are the admin's,
defined for all of AIsolate, on the space or on the airlock: the project names them by id and
holds no credential. The airlock is created or updated from `aisolate.yaml` on every deploy: its
name, its description and its own users; a field the file leaves out keeps what the AIsolate UI
set.

## The tree

```
aisolate.yaml                     the airlock: its name, description and settings
.env, .env.example                  AISOLATE_ENDPOINT, AISOLATE_SPACE; .env is never committed
src/main/java/com/acme/hr/   the code, by stage
  pre_llm/ContractsContextPreparator.java   ContextPreparator
  pre_llm/SessionMemory.java                ConversationMemory
src/test/java/com/acme/hr/   the tests, with FakeAIsolateManager standing in for AIsolate
build.gradle.kts                    one jar, named after the template
settings.gradle.kts                 the project's name: the template, company-hr
libs/aisolate-sdk.jar             the AIsolate SDK, the one thing the project compiles against
gradle/, gradlew, gradlew.bat       the Gradle wrapper; it provisions JDK 25 when the machine has none
```

## The stages

Every class of the project belongs to this airlock, whatever its package. The folders sort the
code by the stage of the exchange it runs in; they are a convention for reading, and AIsolate
reads the stage from the interface the class implements.

| Folder | Holds | Runs |
|---|---|---|
| `pre_llm/` | `ContextPreparator` handlers, which search documents and add passages as evidence; `ConversationMemory` handlers, which recall and remember per user | before the model sees the question |

Another project may add `tools/`, `ToolPlugin` classes whose tools the model calls while it writes
the answer, and `post_llm/`, `AnswerVerifier` handlers that judge the finished answer; this one has
neither: the contracts are all it reads.

Nothing is registered anywhere: AIsolate scans the classes. A handler may be package-private; it
needs a no-argument constructor.

The template's classes: `ContractsContextPreparator` searches the employment contracts in the data
source `hr` for the sections the asking user may read and adds them to the exchange as evidence.
`SessionMemory` keeps each user's last three questions (200 characters each) while the airlock's
process runs. Grounding is AIsolate's: it checks that each quote is the contract's own words and
flags every number typed outside a quote (`docs/verification.md`).

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
| `llm`, `prompt`, `tools` | the id of the LLM that answers, `granite`; the `prompt`; the `tools` the model may call, none here |
| `postLLM` | `answerVerifier.handlers`, none here |
| `llms`, `dataSources` | optional, none here: LLMs and databases of the airlock's own, each replacing an inherited one of the same id |

Who may ask: the space lists its users, by e-mail, and the
airlock its own, `access.users`, and `access.callers`, the clients that may call (`*` for any,
the default). A space's users may ask every airlock of the space; an airlock's own users may ask
that airlock only. The token's roles do not admit anyone. What a context preparator passes on is
its own code's choice: the contracts handler filters by the asking user's roles and e-mail (see
The contracts). Handlers are listed by class simple name, and are the project's: the deploy
refuses one the code does not have, and warns about a handler listed nowhere, which runs nowhere.
A handler may name itself in a short title, two to four words, with `@Description("...")` ("Read
employment contracts"); the AIsolate UI shows it in the airlock's graph, and the class name when
there is none. It may name the data sources it reads, `@DataSources("hr")`, which the graph shows
as its Source. `llm` names an LLM the airlock sees: its own, its space's or a
global one; the deploy refuses another, `llm: no model named granite in this airlock's space or
globally`. The code names any of them by id at run time, `aisolate.llm("nomic")`, which the
deploy does not check.

A deploy replaces the airlock's code and settings in place.

## Data sources

A data source is a PostgreSQL database by JDBC URL, named by an id of lowercase letters, digits,
`_` or `-`; with pgvector the same database holds the rows and the embeddings. The admin defines
them, with their credentials, for all of AIsolate, on the space or on the airlock, and the airlock
reads them all by id: the global ones, the space's, then its own, where the same id nearer
replaces the farther one.

The code reaches a data source by its id alone, never by URL, credential or variable: a handler
asks for one, `aisolate.dataSource("hr")`, which returns a `javax.sql.DataSource`. An id the
airlock does not define throws `IllegalArgumentException`, `${SPACE}/${AIRLOCK} has no data
source hr`. The PostgreSQL driver comes with the airlock.

The template's one data source, `hr`, which the admin defines, is the `hr` schema of the company's
database, opened as a role that reads `hr.documents` and writes nothing but its vectors.

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

## The contracts

`ContractsContextPreparator` reads the `hr.documents` table of the data source `hr`, PostgreSQL
with pgvector, through `aisolate.dataSource("hr")`: one row per section of an employment contract
(position and duties, compensation, working time and vacation, notice period, confidentiality),
the employee's e-mail as its owner. It embeds, with the LLM `nomic` (`aisolate.llm("nomic")`), the
sections that have no embedding, then the question, and adds up to five of the closest sections
the asking user may read: the CEO, the role `ceo`, reads every contract, anyone else only the
contract that names their e-mail as owner. The asking user's own sections are marked as theirs, so
"my notice period" is answered from their own contract. When the airlock sees no data source `hr`
or no model `nomic` that embeds, or the database does not answer within a few seconds, the handler
logs one line, adds no passages and the model says it does not know.

## Ask it

In the demo, Jane Doe (`jane`) is the CEO and John Doe (`john`) an employee. Each answer quotes
the contract; a number appears only inside a quote.

John sees only his own contract:

1. What is my notice period? (three months to the end of a calendar month)
2. What is Jane Doe's salary? (I don't know: her contract never reaches his exchange)

Jane, the CEO, reads every contract:

1. What is John Doe's notice period? (three months to the end of a calendar month)

## Commands

- `./gradlew build` compiles, runs the tests and writes `build/libs/company-hr.jar`.
- `./gradlew test` runs the tests only.
- `aisolate login` signs you in through your browser, once; the session lasts while you use it.
- `aisolate build` builds the project with its Gradle wrapper.
- `aisolate deploy` checks your session, builds, and deploys the build and `aisolate.yaml` in
  one call into the space `AISOLATE_SPACE` names. It sends the file as written, with the value
  of each variable it reads, prints the deploy's warnings with a `!`, then the airlock
  (`airlock ${SPACE}/${AIRLOCK} (created, revision 1)`, or `updated`), its endpoint, and the
  handlers of its code.
