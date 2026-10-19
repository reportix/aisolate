# Develop an airlock

A project is one airlock: its code (tools, handlers and rule sets) and one `aisolate.yaml` with its
`Airlock`. The space exists first, created in the AIsolate UI: it
lists the users who may ask every airlock of it, and the LLM and the data sources its airlocks share are set
there too. Every deploy creates the airlock from the file's `Airlock`, or updates each field the document
writes (a field it leaves out keeps what the AIsolate UI set). The `aisolate` CLI creates the project from a
template and deploys it into that airlock, in a cluster installed with the AIsolate UI (`headless=false`).
An airlock runs only as a pod: the deploy becomes a pod pair there, and a chat such as
the sample in the `chat` repository asks it.

## Install the CLI

Install the `aisolate` CLI on macOS or Windows, or build it yourself: [Command-line interface](cli.md).

The CLI bundles the SDKs of the checkout it was built from, the Java one (`libs/aisolate-sdk.jar`) and the
Python one (`libs/sdk-python`, written into a Python project as `libs/aisolate-sdk/`). Rebuild it after
pulling. A Java project builds with its own Gradle wrapper, which provisions JDK 25. A Python project builds
with [uv](https://docs.astral.sh/uv/), which installs Python 3.13 when the machine has none, or, without uv,
with `python3.13` on the PATH; with neither the build stops with
`a Python project builds with uv (https://docs.astral.sh/uv/) or Python 3.13: …`.

## Create a project

The space exists first: create it in the AIsolate UI
([Define airlocks in the AIsolate UI](#define-airlocks-in-the-aisolate-ui)); the first deploy creates the
template's airlock. Then:

```bash
aisolate create -s=hr-space -t=company-hr     # offline
cd company-hr
```

The project is Python by default; `--language=java` (`-l`) writes the same template in Java instead. Every template
exists in both languages, with the same `aisolate.yaml`, the same `rules/*.json`, the same tools, entities and
handlers by name: the airlock behaves the same, only the pods' images differ. A Python project is
`pyproject.toml`, `src/<package>/` (`tools/`, `pre_llm/`, `post_llm/`), `rules/`, `tests/` (pytest) and the SDK
in `libs/aisolate-sdk/`.

`create` writes the project into a directory named after the template, `./company-hr`, with
`AISOLATE_SPACE=hr-space` in `.env` and the project's name, `company-hr`, in `pyproject.toml` (in
`settings.gradle.kts` for Java).
Each template is one airlock, its own by default (the
auditor has none); `--airlock=<id>` (`-a`) writes
another airlock's id into `metadata.name` and names the directory
`./<template>-<id>`. The repository stays the airlock's source of truth: the deploy never changes the
airlock it names. The CLI ships four templates:

| Template | Airlock by default | Asked about |
|---|---|---|
| `company-hr` | `hr-contracts` | the employment contracts (position, pay, working time, notice, confidentiality): the CEO reads every contract, anyone else their own |
| `company-sales` | `sales` | client accounts, invoices and the client contracts, by the founders |
| `auditor` | none: `--airlock=<company>` is required | one audited company's books, minutes and policies, by the audit firm's auditors: one airlock per company, each defining its own data source `ledger` in the AIsolate UI |
| `hospital` | `cardiology` | one department's part of the hospital's one health record, the space's data source `ehr`: `--airlock=cardiology` or `--airlock=psychiatry` decides which tools the airlock offers and whose notes it reads |

`company-hr` and `company-sales` deploy one project each, in the demo into two spaces, and bring their own
data source; `auditor` and `hospital` bring none, so the code of every company's or department's airlock is
the same and the airlock decides what it reads.

```
company-hr/
  aisolate.yaml                     the Airlock: its name, its own users, the four groups, its LLM by id
  .env, .env.example                  AISOLATE_ENDPOINT, AISOLATE_SPACE, no credential; .env is never committed
  src/acme_hr/                      every class of the package belongs to this airlock
    pre_llm/contracts.py              ContractsContextPreparator, a @context_preparator: reads hr.documents on hr
    pre_llm/memory.py                 SessionMemory, a @memory
  tests/                              pytest, with tests/support's FakeAIsolateManager standing in for AIsolate
  pyproject.toml                      the name, Python 3.13 and the one dependency, the SDK in libs/aisolate-sdk/
  README.md, .gitignore               what each file does; .gitignore keeps .env and build/ out of git
```

With `--language=java` the same project is a Gradle build, the same `aisolate.yaml` and `.env`:

```
company-hr/
  src/main/java/com/acme/hr/   every class belongs to this airlock
    pre_llm/ContractsContextPreparator.java   ContextPreparator, reads hr.documents on the data source hr
    pre_llm/SessionMemory.java                ConversationMemory
  src/test/java/com/acme/hr/   the tests, with FakeAIsolateManager standing in for AIsolate
  build.gradle.kts                    one jar, named after the template
  settings.gradle.kts, gradlew, gradle/, libs/aisolate-sdk.jar
  README.md, .gitignore               what each file does; .gitignore keeps .env and build/ out of git
```

`company-hr` has no tools, no rules and no verifier: the contracts are all it reads. `company-sales/` adds them:
`rules/clients.accounts.json` and `rules/clients.invoices.json`, the package `acme_sales` (`com/acme/sales/` in
Java) with `TodayContext`, `SalesDocumentsContextPreparator`, `SessionMemory`, `SalesTools` and
`CustomerVerifier`, and the data source `clients`; its Java build ships `rules/` at
`aisolate/rules/<entity>.json`.

`.env` holds `AISOLATE_ENDPOINT`, `AISOLATE_SPACE` and the variables the file reads
([.env](documents.md#env)). `libs/aisolate-sdk/` (`libs/aisolate-sdk.jar` in Java) is the one AIsolate library the
project depends on. The project's own `README.md` says what each file does.

## Where code goes

Every class of the project belongs to the airlock its `aisolate.yaml` names, in any package; nothing is
registered. The pods and the backend scan the classes, package-private or not; each plugin and handler needs a
no-argument constructor. The templates' `pre_llm/`, `tools/` and `post_llm/` folders sort the classes by the
stage they run in, and nothing parses them.

## Write tools

A tool plugin implements `ToolPlugin` from the SDK. It declares:

- the entities: each `Entity` with its `Column`s and its row key, the allow-list of what a tool may query,
  and its subject when a row is about someone other than its row key names (`withSubject("patient_id")`):
  a minimum group counts distinct subjects, and only an entity whose subject is named like the identity
  attribute has rows marked `self`;
- each entity's labels, which AIsolate shows every figure an answer cites with and checks the model's claims
  against: the column naming whom a row is about for a reader (`withSubjectName("full_name")`), its period
  (`withPeriod("year")`) and each amount's unit, fixed (`Column.withUnit("EUR")`, `"days"`, `"%"`) or from a
  column of the row (`withUnitFrom("currency")`); and its fact columns, text columns whose values the model must
  cite, not type (`Column.asFact()`, Python `fact=True`: a drug, a dose, a diagnosis)
  ([Verification](verification.md#the-labels));
- `dataSource()`: the id of the data source the entities live in, one the airlock resolves;
- the identity join: the token's e-mail to the organisation's business key, on that data source;
- the tools: each `Tool` with its name and its arguments.

A tool reaches data only through the `GovernedQuery` the MCP server hands it (`read`, `aggregate`), on its
plugin's data source, and returns the `Result` it gets, or one built from its rows: anything else fails the
call with a generic error. It holds no connection and no rules: the MCP server compiles the rules into the SQL and
builds it from the entity's own expressions, never from model text. Within one airlock's code a tool name is
published once and an entity declared once. A column is a SQL expression the plugin writes: a date column is
exposed as ISO text (`o.updated_on::text`), so the model filters it with `gte` and `lt`; a derived column such
as an invoice's status is a `CASE` expression. The `company-sales` template's `SalesTools` declares
`clients.accounts` and `clients.invoices` on `clients`, with no identity join, because no row is anyone's own,
and publishes `read_records` (at most 20 rows) and `aggregate_records`; each takes the entity it reads as an
argument, an enum of the plugin's entities.

## Write handlers

| Interface | Stage | Does |
|---|---|---|
| `ContextPreparator` | Pre-LLM, in the airlock pod before the model is asked | Reads its data source as that source's role, keeps what the user's roles and e-mail allow, returns passages. They enter the context under an evidence id. Embeds through `aisolate.llm(id).embed`. |
| `ConversationMemory` | Pre-LLM before the model, and again after the answer | Recalls what the airlock remembers about the user, and remembers. |
| `AnswerVerifier` | Post-LLM, after the answer | Judges the grounded answer (its figures and their sources, the question, the evidence) and annotates it with a verdict. Deterministic code of the project. Never blocks. |

An airlock runs the handlers it lists, by class simple name, in `preLLM.contextPreparator.handlers`,
`preLLM.conversationMemory.handlers` and `postLLM.answerVerifier.handlers`. A handler listed nowhere exists but
runs nowhere. A handler may name itself in a short title, two to four words, with `@Description("…")`
(`com.reportix.aisolate.sdk.Description`), such as "Read employment contracts"; the AIsolate UI shows that title in the
airlock's graph, and the class name when there is none. It may name the data sources it reads, `@DataSources("hr")`, which the graph shows as its Source. Within a stage the handlers run by `order()`, 0 unless the class overrides it, then by name. The pods fetch the airlock's code once
at start from the backend and check its digest; code that does not arrive whole or does not load fails the start.

A handler reads a data source through `AIsolateManager.dataSource(id)`: a `javax.sql.DataSource` on a data
source the airlock resolves. An id the airlock does not resolve throws `IllegalArgumentException`
(`hr-space/hr-contracts has no data source payroll`). The code reads no environment variable for a connection.

A handler reaches an LLM through `AIsolateManager.llm(id)`, by its id in the catalog the airlock sees: its own
LLMs, its space's and the global ones ([LLMs and data sources](llms-and-data-sources.md)). The same
LLM embeds (`embed(text)`, `embed(texts)`: one vector per text) and chats (`chat(prompt)`), as far as its model
can: `aisolate.llm("nomic").embed(exchange.question())`. An unknown id, an embed on a model that cannot embed and a
chat on one that cannot chat throw `LlmException`, an `IllegalArgumentException` with a plain message (`no model
named gemma in this airlock's space or globally`, `granite4.1:8b (model granite) cannot embed: name an embedding model
such as nomic in aisolate.llm(...)`); the pod logs a handler failing with it as `handler <name> failed at <stage>:
<message>`, any other failure by its type alone. Ids are known only at run time: a deploy never checks them.

The templates' handlers: `ContractsContextPreparator` reads `hr.documents` from the data source
`hr` with pgvector and keeps at most 5 sections within cosine distance 0.6, with the owner predicate in the
`WHERE` clause (the CEO reads every contract, anyone else their own) and the asking user's own sections marked as
theirs; `SalesDocumentsContextPreparator` reads `clients.documents` from
`clients` the same way and filters nothing: every user the Sales airlock admits, the space's users among
them, reads every client document, by design, so the space lists only people who may read them all;
`SessionMemory` keeps each user's last three questions while the pod runs. The verifiers each judge one rule of their
template's questions: company-sales' `CustomerVerifier` (every figure is about the customer the question names), the
auditor's `PeriodVerifier` (every figure is for a year the question names) and the hospital's `LatestReadingVerifier`
(a question for the latest reading is answered with the latest row), `FormularyVerifier` (every drug the answer names
is in the records the tools returned) and `PatientVerifier` (every figure is about the patient the question names);
company-hr, the hr-contracts airlock, has none. AIsolate's grounding writes every figure of the answer in from the
evidence and flags every number the model typed itself, and every value of a fact column
([Verification](verification.md)). A verifier gets the grounded answer and each evidence as JSON text
(`groundingJson()` and `contentJson()` in Java, `grounding_json` and `content_json` in Python); Java project code reads
it with `com.reportix.aisolate.sdk.Json.parse`, Python with `json`. The platform ships no retriever, no document
parser and no injection filter: the handler's predicates are the
developer's invariant.

## Write rules

A rule set is `rules/<entity>.json`, one file per entity; the build ships it at `aisolate/rules/<entity>.json`
in the airlock's code. The format is in [Rule set](documents.md#rule-set). Rules ship in the code; the AIsolate UI has no rule
builder. The deploy refuses code whose rule sets do not parse or sit anywhere but
`aisolate/rules/<entity>.json`.

## Write the project file

`aisolate.yaml` is the airlock's one `Airlock` document: its name, description and own users (created or updated on
every deploy, a field it leaves out keeping what the AIsolate UI set), and the four groups: the LLM that answers by its
id (`spec.llm: granite`) with the `prompt` and `tools` under `spec`. It holds no credential: the LLMs and the data
sources its code reads by id are the admin's, for all of AIsolate, on the space or on the airlock. Who may ask: the space lists its users in the AIsolate UI, the
airlock its own and its callers, in the file or in the AIsolate UI. The file as the template writes it, and what the
deploy checks, are in [The project file](documents.md#the-project-file). An airlock created in the AIsolate UI
has a name, a description, its users and no code; the deploy fills its code, stages, LLM, prompt and
tools from the project, and replaces its description with the project's `spec.description`, blank when
the project has none. Another airlock of the same
space, with other code, is the same project with another `metadata.name`, deployed: the deploy creates it, and
the first keeps running with its own code.

## Set the variables

A value in `aisolate.yaml` may read a variable: `${NAME}`, or `${NAME:-fallback}` when NAME may stay unset.
A data source an airlock defines itself gives its `username` and `password` this way, so no credential is ever
committed; the templates define none.
`aisolate deploy` reads `.env` at the project root; a variable set in the shell wins. It refuses, before the
build, a variable that is unset or empty and has no fallback, and names it. `AISOLATE_SPACE` names the space
the project deploys into; without it `login`, `logout`, `build` and `deploy` stop with
`AISOLATE_SPACE is not set: add AISOLATE_SPACE=<space> to .env`.

## Sign in

```bash
aisolate login     # once per endpoint
aisolate logout
```

`login` shows "Sign in with your account". Enter, or a click, opens your browser on the identity provider's
sign-in page (client `aisolate-cli`, PKCE with a redirect to `127.0.0.1`); the URL is printed only when no
browser can be opened. Sign in as a developer of the space you deploy into, `jasmine` in the demo: the admin
makes you one on the space's **Users** page; anyone else gets `You are not a developer of space acme: ask its
admin to add you as Developer.` The session is kept
per endpoint in `~/.aisolate/session.json`, readable by you only (`AISOLATE_HOME` moves it), refreshed
silently, and ends after 90 days unused or with `logout`.

## Deploy

```bash
aisolate build     # build/aisolate/acme_hr.zip; a Java project: ./gradlew build, build/libs/company-hr.jar
aisolate deploy    # signs you in first when there is no session, builds, then one call
```

A project with a `pyproject.toml` is Python: `aisolate build` runs `uv run python -m aisolate build` (or
`python3.13 -m aisolate build` with the SDK on its path) and writes `build/aisolate/<package>.zip`, the
package, the rules and `aisolate-manifest.json`; the deploy sends that zip with `language` `python`. The
backend reads the manifest and never runs the project's code; the Python airlock and tool server import it.

The project's own dependencies (`[project] dependencies` beyond the SDK) travel in the zip's `vendor/`: uv installs
them as Linux binary wheels for Python 3.13 on the cluster's CPU, by default this machine's (`aarch64` on Apple
Silicon; `AISOLATE_ARCH=x86_64 aisolate deploy` for an x86_64 cluster). The runtime's own packages
come from the pods, never the zip: the build refuses a dependency that needs one of them
at another version, naming the package and both versions, and a package without a Linux wheel, naming it. Without uv, a project with dependencies of its own does not
build: install uv. The templates depend on nothing else.

The zip has no size limit unless the install sets one: each pod fetches it from the backend at start, so a binary
package such as numpy travels like any other. The manifest records the CPU it was built for, and a pod on another
CPU refuses to start, naming the `--arch` to rebuild with.

One call sends the built project and `aisolate.yaml` as written, with the values of the variables it reads,
to `POST /v1/spaces/{space}/deploy`, the space being `AISOLATE_SPACE`. The backend:

- reads the code: its tools, each with its plugin and data source, its handlers and its rule sets;
- checks the file: one `Airlock`, its `metadata.name` the id of an airlock of the space, or of one to create;
- checks the file against the code: every tool in `tools` and every handler the file lists is the code's;
- checks the airlock with the global and the space's documents: its `llm` names an LLM it sees
  (`llm: no model named granite in this airlock's space or globally`), and every data source a tool plugin names
  is defined at one of the three levels;
- creates the airlock when its id is new, or updates it in place with the new code and bumps its revision,
  in one transaction;
- turns the credentials of each data source the airlock defines itself into a Secret owned by the airlock;
- applies the space to the cluster.

Nothing is deleted. Every class of the project
belongs to this airlock. The CLI prints the deploy's warnings, then the airlock with its change count
(`revision 1`, one more on every save or deploy; not the revision of its documents):

```
⏺  Signed in as jasmine
⏺  Building company-hr
✔  Built build/libs/company-hr.jar
⏺  Deploying to space hr-space
✔  hr-space/hr-contracts (created, revision 1)
   POST https://aisolate.example.com/v1/spaces/hr-space/airlocks/hr-contracts/chat
   code sha256:…; tools: none; handlers: ContractsContextPreparator, SessionMemory
   cluster: applied, the status of the Airlock shows its pods
```

A refusal prints its reasons and exits 1. Without a terminal (CI,
a pipe) a missing session stops the deploy before the build. The code has no size limit unless the install sets one
(`platform.backend.maxArtifactSize`, refused before anything is stored); the pods fetch it from the backend at
start, so a bigger artifact starts more slowly. Within a minute
`kubectl -n aisolate get airlocks` shows `hr-contracts` Ready ([Check status](release.md#check-status)).
The second project deploys the same way: `aisolate create -s=acme-financial-group -t=company-sales`, then `deploy`
in `./company-sales` creates `acme-financial-group/sales` with its own pod pair.

## Define airlocks in the AIsolate UI

The AIsolate UI is a view of the same objects a deploy writes. Who does what is GitLab's group model: the admin
(`aisolate-admin`) creates a space and adds its users, each with the role Developer or User; a developer sees the
spaces they develop, creates and edits their airlocks and deploys into them, and sees the space's
own Users, LLMs and Data sources read-only; a user only asks, in the chat, and sees no space here. Every list, add and edit is a page of its own with its own breadcrumb, and a save applies at once. The
rail follows where you are: Dashboard and Spaces; inside a space, its Users, LLMs and Data sources; inside an
airlock, its Overview, Details, Tools, Users, LLMs and Data sources. In the order a project is set up:

- **New space** (the admin's): a name and a description; the id is generated from the name. The space's own
  pages come next, the admin's to change. **Users**: everyone who may ask every airlock of the space, by name and
  e-mail, with a Role, Developer or User, which the admin changes in the row; **Add users** picks people from the
  directory and gives them the role chosen (User unless Developer is picked), and the list may be empty. **LLMs**:
  the global ones, inherited, and the space's own; **Add LLM** takes an id, a provider, a URL and a model, and an
  own id that a global one has replaces it. An LLM has no type: the same one chats or embeds, as far as its model
  can. **Data sources**: a table of ids with their URL and credentials; **Add data
  source** takes an id, a URL, a user and a password, a row's pencil edits it and its trash removes it. A
  project deploys into an existing space.
- **New airlock**, in the space: a name and a description; the id is generated from the name and names it
  in its endpoint path (the airlock **HR contracts** gets the id `hr-contracts`, which the template's `metadata.name`
  names). A new airlock starts with no users of its own and the callers `*`. Each of its three pages shows
  the airlock's own first, then what it takes from the space, read-only. **Users**: **Users of** the airlock,
  who may ask it alone and need not be users of the space, then **Inherited from the space**, the space's
  users, then the callers; **Edit users** takes the airlock's own users as one e-mail per line, maybe none, and
  the callers, `*` for any. **LLMs**: the airlock's own rows over the global and the space's;
  a row with the id of an inherited one replaces it for the airlock. **Data sources**: the airlock's own rows over the
  space's; a row with the id of one of the space's replaces it for the airlock. An airlock exists
  before its first deploy, with no code, and runs nothing until it.
- **`aisolate deploy`** into the airlock brings the airlock's code, its stages, its prompt and its data
  sources, and its description from `spec.description` in place of the one typed here ([Deploy](#deploy)).
- **The airlock's own pages**, read-only, with a **Copy endpoint** button. **Overview**: the exchange left to right in the
  order the code runs it, a node for each handler by its short title (context preparators and conversation
  memories, then the LLM by its model, then the answer verifiers); before a deploy, one sentence says what to
  run. **Details**: the endpoint with a **Copy** button, then the same in words with the class names, the LLM
  and where each value comes from, the prompt, and the data sources with their origin. **Tools**: the tools the airlock lists (the ones the model may call), with
  their data source and description, and its rule sets. An airlock without code shows no handlers and no tools
  until `aisolate deploy` brings them; **Edit** changes its name and description, never the code, the stages or
  the prompt.
- A typed user and password become a Secret; the form shows the Secret's name, never a password, and a blank
  password on edit keeps it. Every save applies at once and rolls the pods whose documents it changes onto the
  new revision ([Names, ids and revision](documents.md#names-ids-and-revision)). The UI deletes no space
  or airlock; the trash of a data source row removes the row and its Secret.

## Try the airlocks

Sign in to a chat as a user the space or an airlock lists, one of each role. The demo realm has one user per
role: an employee, the CEO (also a founder and an admin), a co-founder (an employee and a founder), an intern, a
developer and an admin; the realm file lists their logins. With a space `hr-space` that lists the CEO's e-mail and
its `hr-contracts` airlock that lists the employee's as its own, a space that lists the two founders' with a `sales`
airlock with no users of its own, and the two templates deployed, the chat lists the airlocks a user may ask (the
employee sees `hr-contracts` alone, the CEO both, through the spaces), and shows the answer and, folded under it,
what ran: a line per verifier, with its note.

| Who asks | Airlock | Question | What comes back |
|---|---|---|---|
| The employee | contracts | What is my notice period? | "three months to the end of a calendar month", quoted from their own contract, marked as their own; no tool call. |
| The employee | contracts | The CEO's salary, asked by name | "I don't know": the context preparator passes them only their own contract. |
| The CEO | contracts | The employee's notice period, asked by name | "three months to the end of a calendar month": the CEO reads every contract. |
| The co-founder | sales | Which invoices are overdue? | The rows of `clients.invoices` whose `status` is `overdue`, under `founders`. |
| The co-founder | sales | Total contract value by industry? | One row per industry from `aggregate_records`; the founders' rule sets no group minimum. |
| The CEO | sales | What did we agree with Varnholt on the price cap? | The Pricing section of the agreement; evidence with source `client-documents`. |
| The employee | sales | anything | Discovery lists no `sales` airlock for them; its endpoint answers 403. |
| The intern | any | anything | Discovery lists no airlock; the endpoint answers 403. |

Who may ask an airlock is the gate for everything it serves: the space's users and its own. The rules filter
only what the tools return, by the token's roles: a user the airlock admits whose roles match no rule gets zero
rows from the tools, never a 403. A context preparator decides in its own code what it passes on, and the answer
may draw on it: `SalesDocumentsContextPreparator` passes every client document to every user the Sales
airlock admits. So list on the space only the people who may see all of what every airlock's context
preparators read, and give someone who may see one airlock's only that airlock's Users page.

## Start over

`aisolate-reset` removes every space with its airlocks, their code, resources, Secrets and pods,
through the backend and the session it shares with `aisolate`; it needs the `aisolate-admin` role, since it
wipes every team's spaces. Create a space and its airlocks in the AIsolate UI before the next deploy. See the
`aisolate-reset` repository.
