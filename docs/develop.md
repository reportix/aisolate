# Develop an airlock

A project is one airlock: its code (tools, handlers and rule sets) and one `aisolate.yaml` with its
`Airlock`. The space exists first, created in the AIsolate UI: it
lists the roles that may ask every airlock of it, and the LLM and the data sources its airlocks share are set
there too. Every deploy creates the airlock from the file's `Airlock`, or updates each field the document
writes. The `aisolate` CLI creates the project from a
template and deploys it into that airlock, in a cluster installed with the AIsolate UI (`headless=false`).
An airlock runs only as a pod: the deploy becomes a pod pair there, and a chat such as
the sample in `apps/chat` asks it.

## Install the CLI

Install the `aisolate` CLI on macOS or Windows, or build it yourself: [Command-line interface](cli.md).

## Create a project

The space exists first: create it in the AIsolate UI
([Define airlocks in the AIsolate UI](#define-airlocks-in-the-aisolate-ui)); the first deploy creates the
template's airlock. Then:

```bash
aisolate create -s=hr-space -t=company-hr     # offline
cd company-hr
```

The project is Python by default; `--language=java` (`-l`) writes the same template in Java instead.
[The Python artifact](python-artifact.md) has its layout and what its build writes.

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

```
company-hr/
  aisolate.yaml                     the Airlock: its name, its own roles, the four groups, its LLM by id
  .env, .env.example                  AISOLATE_ENDPOINT, AISOLATE_SPACE, no credential; .env is never committed
  src/acme_hr/                      every class of the package belongs to this airlock
    pre_llm/contracts.py              ContractsContextPreparator, a @context_preparator: reads hr.documents on hr
    pre_llm/memory.py                 SessionMemory, a @memory
  tests/                              pytest, with tests/support's FakeAIsolateManager standing in for AIsolate
  pyproject.toml                      the name, Python 3.13 and the one dependency, the SDK in libs/aisolate-sdk/
  README.md, .gitignore               what each file does; .gitignore keeps .env and build/ out of git
```

`.env` holds `AISOLATE_ENDPOINT`, `AISOLATE_SPACE` and the variables the file reads
([.env](documents.md#env)). `libs/aisolate-sdk/` (`libs/aisolate-sdk.jar` in Java) is the one AIsolate library the
project depends on.

## Where code goes

Every class of the project belongs to the airlock its `aisolate.yaml` names, in any package; nothing is
registered. The pods and the backend scan the classes, package-private or not; each plugin and handler needs a
no-argument constructor. The templates' `pre_llm/`, `tools/` and `post_llm/` folders sort the classes by the
stage they run in ([Stages](concepts.md#stages)), and nothing parses them.

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
as an invoice's status is a `CASE` expression.

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
airlock's graph, and the class name when there is none. It may name the data sources it reads, `@DataSources("hr")`, which the graph shows as its Source. Within a stage the handlers run by `order()`, 0 unless the class overrides it, then by name.

A handler reads a data source through `AIsolateManager.dataSource(id)`: a `javax.sql.DataSource` on a data
source the airlock resolves. An id the airlock does not resolve throws `IllegalArgumentException`
(`hr-space/hr-contracts has no data source payroll`). The code reads no environment variable for a connection.

A handler reaches an LLM through `AIsolateManager.llm(id)`, by its id in the catalog the airlock sees: its own
LLMs, its space's and the global ones ([Where LLMs and data sources are defined](concepts.md#where-llms-and-data-sources-are-defined)). The same
LLM embeds (`embed(text)`, `embed(texts)`: one vector per text) and chats (`chat(prompt)`), as far as its model
can: `aisolate.llm("nomic").embed(exchange.question())`. An unknown id, an embed on a model that cannot embed and a
chat on one that cannot chat throw `LlmException`, an `IllegalArgumentException` with a plain message.
Ids are known only at run time: a deploy never checks them.

AIsolate's grounding writes every figure of the answer in from the
evidence and flags every number the model typed itself, and every value of a fact column
([Verification](verification.md)). A verifier gets the grounded answer and each evidence as JSON text
(`groundingJson()` and `contentJson()` in Java, `grounding_json` and `content_json` in Python); Java project code reads
it with `com.reportix.aisolate.sdk.Json.parse`, Python with `json`. The grounded answer is the JSON answer's
`{answer, segments, figures, tables, quotes, provenance}` ([API](api.md#the-chat-endpoint)); a tool's evidence is
its result, `{entity, row_count, columns, rows, rule_version, rules, sql_hash, labels, data_source}`, the labels
`{row_key, subject, period, units, unit_columns, decimals, masked, function, facts}` (`facts` only when a readable
column is one), the values as the database gave them (exact numbers, text, booleans, null, `YYYY-MM-DD` dates);
an empty result has `entity` `""`, `row_count` 0 and `labels` null. The platform ships no retriever, no document
parser and no injection filter: the handler's predicates are the
developer's invariant ([Context preparators](security.md#context-preparators)).

## Write rules

A rule set is `rules/<entity>.json`, one file per entity; the build ships it at `aisolate/rules/<entity>.json`
in the airlock's code. The format is in [Rule set](documents.md#rule-set), the evaluation in
[Rules become SQL](security.md#rules-become-sql). Rules ship in the code; the AIsolate UI has no rule
builder. The deploy refuses code whose rule sets do not parse or sit anywhere but
`aisolate/rules/<entity>.json`.

## Write the project file

`aisolate.yaml` is the airlock's one `Airlock` document: its name, description and own roles,
and the four groups: the LLM that answers by its
id (`spec.llm: granite`) with the `prompt` and `tools` under `spec`. It holds no credential: the LLMs and the data
sources its code reads by id are the admin's, for all of AIsolate, on the space or on the airlock. Who may ask: the space lists its roles in the AIsolate UI, the
airlock its own and its callers, in the file or in the AIsolate UI. The file as the template writes it, and what the
deploy checks, are in [The project file](documents.md#the-project-file). An airlock created in the AIsolate UI
has a name, a description, its roles and no code; the deploy fills its code, stages, LLM, prompt and
tools from the project, and replaces its description with the project's `spec.description`, blank when
the project has none.

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

`login` shows "Sign in with your account". Enter, or a click, opens your browser on the operators' identity provider's
sign-in page (demo realm `aisolate`, client `aisolate-cli`, PKCE with a redirect to `127.0.0.1`); the URL is printed only when no
browser can be opened. Sign in as a developer of the space you deploy into: the admin
names you one of the space's developers.
The session is kept
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
(`aisolate/runtime-constraints.txt`) come from the pods, never the zip: the build refuses a dependency that needs one of them
at another version, naming the package and both versions, and a package without a Linux wheel, naming it
([python-artifact.md](python-artifact.md#the-zip)). Without uv, a project with dependencies of its own does not
build: install uv. The templates depend on nothing else.

The manifest records the CPU it was built for, and a pod on another
CPU refuses to start, naming the `--arch` to rebuild with.

One call sends the built project and `aisolate.yaml` as written, with the values of the variables it reads,
to `POST /v1/spaces/{space}/deploy`, the space being `AISOLATE_SPACE`.
Nothing is deleted. The CLI prints the deploy's warnings, then the airlock with its change count
(`revision 1`, one more on every save or deploy; not the [revision](concepts.md#vocabulary) of its documents):

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

A refusal prints its reasons and exits 1 ([The deploy call](api.md#the-deploy-call)). Without a terminal (CI,
a pipe) a missing session stops the deploy before the build.
Within a minute
`kubectl -n aisolate get airlocks` shows `hr-contracts` Ready ([Check status](release.md#check-status)).

## Define airlocks in the AIsolate UI

The AIsolate UI is a view of the same objects a deploy writes. Who does what is GitLab's group model: the admin
(`aisolate-admin`) creates a space and names its developers, by e-mail, and the roles that may ask it; a developer sees the
spaces they develop, creates and edits their airlocks and deploys into them, and sees the space's
own LLMs and Data sources read-only; anyone else only asks, in the chat, and sees no space here. Every list, add and edit is a page of its own with its own breadcrumb, and a save applies at once. The
rail follows where you are: Dashboard and Spaces; inside a space, its LLMs and Data sources; inside an
airlock, its Overview, Details, Tools, LLMs and Data sources. In the order a project is set up:

- **New space** (the admin's): a name and a description; the id is generated from the name. The space's own
  pages come next, the admin's to change. The form has **Developers**, e-mails typed as chips, who edit the space in the UI, and **Roles**, the
  roles that may ask every airlock of the space (the application users' roles, typed as chips), and either may be empty.
  A
  project deploys into an existing space.
- **New airlock**, in the space: a name and a description; the id is generated from the name and names it
  in its endpoint path (the airlock **HR contracts** gets the id `hr-contracts`, which the template's `metadata.name`
  names). A new airlock starts with no roles of its own and the callers `*`.
  An airlock exists
  before its first deploy, with no code, and runs nothing until it.
- **`aisolate deploy`** into the airlock brings the airlock's code, its stages, its prompt and its data
  sources, and its description from `spec.description` in place of the one typed here ([Deploy](#deploy)).

## Try the airlocks

Sign in to a chat as an application user whose token carries a role the space or an airlock lists. Realm `acme` has an employee, the CEO (also a founder), a co-founder (an employee and a founder), an intern and a
user without roles; `docker/keycloak/realm-acme.json` lists their logins. With a space `hr-space` that lists the role `ceo` and
its `hr-contracts` airlock that lists `employee` as its own, a space that lists `founder` with a `sales`
airlock with no roles of its own, and the two templates deployed, the chat lists the airlocks a user may ask (the
employee sees `hr-contracts` alone, the CEO both, through the spaces), and shows the answer and, folded under it,
what ran: a line per verifier, with its note.

| Who asks | Airlock | Question | What comes back |
|---|---|---|---|
| The employee | contracts | What is my notice period? | "three months to the end of a calendar month", quoted from their own contract, marked as their own; no tool call. |
| The employee | contracts | The CEO's salary, asked by name | "I don't know": the context preparator passes them only their own contract. |
| The CEO | contracts | The employee's notice period, asked by name | "three months to the end of a calendar month": the CEO reads every contract. |

## Start over

An admin calls `DELETE /spaces` ([API](api.md#endpoints)), which removes every space with its airlocks, their
code, resources, Secrets and pods; it needs the `aisolate-admin` role, since it wipes every team's spaces. Create a
space and its airlocks in the AIsolate UI before the next deploy.
