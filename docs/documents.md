# Documents

An airlock is defined by two kinds of document in the API group `aisolate.dev/v1`: `Space` and `Airlock`,
beside the install's one `Global`, which holds the LLMs and data sources every space inherits
([Where LLMs and data sources are defined](concepts.md#where-llms-and-data-sources-are-defined)). An airlock names its
space (`spec.space`).
A project's `aisolate.yaml` writes one `Airlock`, which every deploy creates or updates; the space is defined
in the AIsolate UI. The backend keeps all three and applies them to the cluster. The same loader validates them
in every process. The documents below are a complete example of the three kinds.

## Example documents

The space `hr-space` after the `company-hr` template's deploy, into an install
whose admin defined the LLMs `granite` and `nomic` and the data source `hr` for all of AIsolate, and a space that adds a larger model. It holds URLs and Secret names, never a credential, and no code: each airlock names its
code by digest, and its pods fetch it from the backend.

```yaml
apiVersion: aisolate.dev/v1
kind: Global
metadata: {name: aisolate}                       # the install's one, set by the admin in the AIsolate UI
spec:
  llms:                                          # every space's, unless a nearer level defines the same id
    - {id: granite, provider: ollama, url: http://ollama.example.com:11434, model: "granite4.1:8b"}
    - {id: nomic, provider: ollama, url: http://ollama.example.com:11434, model: nomic-embed-text}
  dataSources:                                   # every space's too: the hr schema, which ContractsContextPreparator names
    - {id: hr, url: "jdbc:postgresql://acme-db.aisolate.svc:5432/acme", secretRef: aisolate-ds-hr-x7k2qa}   # keys username and password
---
apiVersion: aisolate.dev/v1
kind: Space
metadata: {name: hr-space}
spec:
  displayName: hr-space
  description: "The employment contracts of Acme's people."
  access:
    roles: [ceo]                              # who may ask every airlock of the space; may be empty; the identity provider's role names, exact
  llms:                                          # its airlocks', beside the global ones
    - {id: qwen, provider: ollama, url: http://ollama.example.com:11434, model: "qwen3.6:27b-q4_K_M"}
  dataSources: []                                # shared by every airlock of the space
---
apiVersion: aisolate.dev/v1
kind: Airlock
metadata: {name: hr-contracts}                      # the id: a global slug, in the endpoint path
spec:
  space: hr-space
  displayName: HR contracts
  description: "Employment contracts: position, pay, working time, notice and confidentiality."
  access:                                        # who may ask the airlock besides the space's roles
    roles: [employee]                         # its own roles, which may ask it alone; need not be the space's; may be empty; case matters
    callers: ["*"]                               # at least one; "*": any client the identity provider issued an aisolate token to
    # clients: [etl]                             # machine clients: a token without an e-mail whose azp is one of these, exact, no "*"; absent when none
  preLLM:
    contextPreparator:
      handlers: [ContractsContextPreparator]
    conversationMemory:
      handlers: [SessionMemory]
  llm: granite                                   # the LLM that answers, by id: the global one here
  prompt: >-
    You are the Acme contracts assistant. Answer only from the employment contract passages
    provided to you; each passage's title names whose contract it is. Quote the words that
    answer; when no passage answers, reply exactly: I don't know.
  # tools: [read_records]                        # the tools the model may call, the code's; none here
  # postLLM:
  #   answerVerifier:
  #     handlers: [CustomerVerifier]             # the answer verifiers, after the model; none here
  llms: []                                       # none of its own
  dataSources: []
  jar:                                           # the airlock's code, set by the deploy
    digest: "sha256:…"
    tools: []                                    # the tools the code publishes
    # language: python                           # a Python project's zip; absent for a jar
```

## The project file

`aisolate.yaml` as `aisolate create -s=hr-space -t=company-hr` writes it. `aisolate deploy`
sends it as written, with the values of the variables it reads.

```yaml
# The contracts airlock, in the order an exchange runs. The space it deploys into is
# AISOLATE_SPACE in .env; the space is created in the AIsolate UI, where it lists its roles. The
# LLMs and data sources are the admin's, defined for all of AIsolate or on the space or the
# airlock: this file names them by id and holds no credential.
# The Airlock below is created or updated on every deploy: each field it writes replaces
# the airlock's, a field left out keeps what the AIsolate UI set.
apiVersion: aisolate.dev/v1
kind: Airlock
metadata:
  name: hr-contracts
spec:
  displayName: HR contracts
  description: "Employment contracts: position, pay, working time, notice and confidentiality."
  access:
    roles: [employee]   # its own roles, besides the space's

  # Pre-LLM, before the model sees the question: what shapes it, and what it reads.
  preLLM:
    contextPreparator:
      handlers: [ContractsContextPreparator]
    conversationMemory:
      handlers: [SessionMemory]

  # LLM, the model writes the answer: the LLM granite, by id. The admin defines the LLMs, for
  # all of AIsolate or on the space or the airlock; the code names one the same way, as the
  # context preparator's aisolate.llm("nomic") does to embed. No tools: the contracts are all
  # it reads.
  llm: granite
  prompt: >-
    You are the Acme contracts assistant. Answer only from the employment contract passages
    provided to you; each passage's title names whose contract it is. A question about my
    contract (me, my, I) is about the asking user's own contract, the passages marked as theirs;
    a question about a person is answered only from that person's contract. Quote the words
    that answer, copied from the passage character for character: the text holds no digit, and
    every number is only inside a quote. When no passage answers, reply exactly: I don't know.
    Your reply is only the JSON object of the answer format, for example
    {"text":"After the probation period your notice period is {q1}.","figures":[],"quotes":[{"id":"q1","passage":{"evidence":"ev-1","index":2},"text":"three months to the end of a calendar month"}],"dont_know":false}.

  # No dataSources and no credentials: the code names its database by id, a handler with
  # aisolate.dataSource(id), here hr. The admin defines it, with its role's password, for all of
  # AIsolate or on the space or the airlock.
```

| In the project file | Rule |
|---|---|
| One `Airlock` | A project is one `Airlock`. A `Space` in the file is refused (the space is `AISOLATE_SPACE`'s, defined in the AIsolate UI), and so is a second `Airlock`. |
| The `Airlock` | Every deploy applies it, in one transaction with the code. An airlock the space does not have is created from it (its name the `displayName`, else its id; callers `*` unless it sets them). An existing one gets each field the document writes: `displayName`, `description`, `access.roles`, `access.callers`, `access.clients`, the three stages, `llm`, `prompt`, `tools`, `llms`, `dataSources`; a field it leaves out keeps what the AIsolate UI or an earlier deploy set, so roles added there survive a document without `access.roles`, and machine clients one without `access.clients`. Nothing different, nothing saved. Data source credentials work as below. `spec.space` is refused: the space is `AISOLATE_SPACE`. A refused document saves nothing. |
| `metadata.name` | Required: the airlock's id ([Airlock id](#names-ids-and-revision)), in the space `AISOLATE_SPACE` names. An id the space does not have creates the airlock; an existing one updates it in place. An id another space holds is refused (409). |
| `spec.description` | Optional; shown with the airlock in the AIsolate UI and in discovery. |
| `spec.llm` | The id of the LLM that answers, defined by the file's `llms`, the space or AIsolate (global), nearest first; an airlock without one runs nothing. One that resolves to nothing is refused, 400 `llm: no model named granite in this airlock's space or globally`. Ids that code names (`aisolate.llm("nomic")`) are not checked at deploy. |
| `spec.llms` | Optional: `{id, provider, url, model}`, no type; the same id as an inherited LLM replaces it. `provider` is `ollama` (`url` the server, `http://ollama.aisolate.svc:11434`) or `openai`, any OpenAI-compatible server with its API version in the `url` (`https://api.openai.com/v1`, Ollama's own `http://…:11434/v1`); `model` is the name the server knows. A project's entry takes no key and names no Secret: an `openai` model's API key is typed once in the AIsolate UI ([Credentials](security.md#credentials)). |
| `spec.access` | Who may ask, beside the space's `access.roles`: the airlock's own `access.roles` and its `access.callers`, set here or in the AIsolate UI, and its machine clients, `access.clients`, set here or through the API. |
| An airlock created in the AIsolate UI | It has a name, a description, its roles, callers, LLMs and data sources, and no `llm`, `prompt` or code, so it runs nothing. The deploy fills its code, stages, prompt, `llm`, `llms` and data sources from the project. |
| Credentials | Each data source gives `username` and `password`, usually as variables, where the stored document has `secretRef`. The backend turns them into a Secret and sets its name. A `secretRef` in a project file is refused. |
| `spec.jar` | Set by the deploy from the built project; one in a project file is refused. `spec.jar.language` is `python` for a Python project's zip, which runs on the Python airlock and tool server images; absent (or `java`) for a jar. |
| Variables | `${NAME}`, or `${NAME:-fallback}` when NAME may stay unset, anywhere in the file. The CLI resolves them from the shell, then from `.env`, and refuses an unset one by name. There is no escape: a prompt cannot hold a literal `${amount}`, which reads as the variable `amount`. |
| Code and file | Every tool in `tools` is published by the code, and every handler the file lists is a handler of the code for that stage. A tool of the code the file does not list, and a handler listed nowhere, are warnings in the deploy result. |
| Data sources | Every data source a tool plugin names in `dataSource()` is defined on the airlock, its space or globally. |

## .env

`.env` at the project root holds what the CLI and `aisolate.yaml` read. A variable set in the shell wins.
`aisolate create` writes it from `.env.example`, readable by you only and kept out of git.

| Variable | Template | Value in the template | Read by |
|---|---|---|---|
| `AISOLATE_ENDPOINT` | every one | `http://localhost:8080` | the CLI: where the project deploys; `http://localhost:8080` when unset; https unless it is `localhost` or a `*.localhost` name |
| `AISOLATE_SPACE` | every one | the space given to `create -s` | the CLI: the space the project deploys into; required |

No template carries a credential or a URL: the LLMs and data sources its code names by id are the admin's,
defined for all of AIsolate, on the space or on the airlock in the AIsolate UI.

## Rule set

A rule set lives in the airlock's code at `aisolate/rules/<entity>.json`, copied from the project's
`rules/<entity>.json`; any other path is refused. One file per entity. The `hospital` template's
`cardiology.vitals.json`:

```json
{
  "entity": "cardiology.vitals",
  "classification": "restricted",
  "rules": [
    {"name": "patient-care", "principals": {"role": "doctor"}, "actions": ["read"], "effect": "allow",
     "row_filter": "cardiology_clinician == principal.clinician_id", "columns": {"national_id": "mask", "cardiology_clinician": "hide"}},
    {"name": "chief-statistics", "principals": {"role": "chief"}, "actions": ["aggregate"], "effect": "allow",
     "row_filter": "true", "columns": {"national_id": "mask", "cardiology_clinician": "hide", "patient": "hide", "patient_id": "hide", "vital_id": "hide", "measured_on": "hide"}, "aggregation": {"min_group_size": 5}}
  ]
}
```

| Field | Meaning |
|---|---|
| `entity` | The entity the set governs, as the tool plugin declares it. One set per entity. |
| `classification` | A label (`public`, `internal`, `confidential`, `restricted`). AIsolate records it and enforces nothing on it. |
| `rules[].name` | Reported in every evidence's `rules`. |
| `rules[].principals.role` | The role the rule matches, required on every rule. The only selector. |
| `rules[].actions` | `read`, `aggregate`, or both; never empty. |
| `rules[].effect` | `allow` or `deny`, required. A deny drops every row of the entity for its role and actions, so it takes no `row_filter`, `columns` or `aggregation`. |
| `rules[].row_filter` | `true`; `<column> == principal.<attribute>` with `email`, `sub` or the attribute the identity join provides; or `<column> == '<literal>'` (lowercase letters, digits, `_`, `-`). |
| `rules[].columns` | Per column: `mask` returns `****`, `hide` drops the column. |
| `rules[].aggregation.min_group_size` | The smallest group an aggregate may return, at least 1, counted in people: the distinct values of the entity's subject (`Entity.withSubject`, the row key by default), so five readings of one patient are a group of one; with it, `min` and `max` are refused. It holds per query: see [Residual risks](security.md#residual-risks). |

A set has `entity`, `classification` and `rules`, nothing else. Anything else, an unknown or duplicate key
included, is rejected when the set is parsed, so free text never reaches SQL. How the rules are evaluated and
compiled: [Rules become SQL](security.md#rules-become-sql).

## Names, ids and revision

| Name | Form |
|---|---|
| Airlock id | A global slug, `^[a-z]([a-z0-9-]{0,39}[a-z0-9])$`, generated from the name (`-2`, `-3`, … when taken or when its cluster name would collide). It names the airlock in the endpoint path, after its space's id. |
| Cluster name | `<space>-<airlock>`, unique across the install and never a space's id: `ab` with `cd-ef` and `ab-cd` with `ef` would share pods and Secrets, so the second is refused (409). Messages, logs and the CLI write an airlock `<space>/<airlock>`: `hr-space/hr-contracts`. |
| Data source id, LLM id | `^[a-z0-9][a-z0-9_-]{0,40}$`, once per list. The same id globally, on the space and the airlock is one data source (one LLM), the nearest definition winning. |
| Pods | `<space>-<airlock>-airlock` and `<space>-<airlock>-mcp-server`. The longer one is at most 63 characters, Kubernetes' limit: an airlock that leaves no room is refused when created (400), naming the length. |
| ConfigMaps | `<space>-<airlock>-config` (the documents the airlock serves), `aisolate-discovery` (`<space>.json` per valid space: what discovery lists, no document). |
| Secrets | One per data source, keys `username` and `password`, named after the document that defines it: `aisolate-ds-<id>-<6 chars>` (global), `<space>-ds-<id>-<6 chars>`, `<space>-<airlock>-ds-<id>-<6 chars>`, an `_` of the id written `-`. The controller's per airlock: `<space>-<airlock>-audit` (the airlock pod's ingest token) and `<space>-<airlock>-code` (both pods' code token). |

An airlock's **revision** is twelve hex digits of the SHA-256 of the documents it serves: the `Global`, its `Space`
and itself. Both of its pods carry it (`AISOLATE_CONFIG_REVISION`), every response carries
`x-aisolate-revision`, every MCP call carries `_meta.revision`, and every evidence's `rule_version` starts
with it. A change to an airlock restarts only that airlock's pods, a change to a space those of every airlock in
it, a change to a global LLM or data source every airlock's. The MCP server answers only for the revision
it loaded, so an exchange that spans a rollout may end in "I don't know".

## Validation

The loader refuses a set of documents and names the file, the document and the field for:

- an unknown field;
- a `Space` that lists a blank, spaced or repeated role, or `*`; a space may list none;
- an `Airlock` without `spec.space`, or whose `spec.space` is no `Space`; without `access.callers`, at least
  one; that lists a blank, spaced or repeated role, or `*`; its own roles may be none, and need not be the
  space's; whose `access.clients` lists a blank, repeated or spaced client id, or `*`; it may list none; whose
  `llm` resolves to nothing (`hr: llm granite is no model of it, of Space acme or of AIsolate`); one without
  `llm` or `prompt` is valid and runs nothing;
- a second `Global`, or one not named `aisolate`;
- an LLM without an id, with a bad id or one its list holds twice, without a `url` or a `model`, or with a
  provider the airlock pod has no client for (`ollama` and `openai` are the providers), or with an `apiKey`: an
  `openai` model names its key's Secret (`secretRef`), which only AIsolate sets;
- a data source without an id, with an id other than lowercase letters, digits, `_` or `-`, or with an id its
  document lists twice;
- an airlock that lists tools or handlers but has no `spec.jar`; with one: no `jar.digest`, or a tool in
  `tools` that is not one of `jar.tools`;
- a blank or repeated name in a list of roles, callers, machine clients, tools or handlers;
- a URL that carries a credential (a user or password in `user[:password]@`, or a `user`, `username`,
  `password`, `token`, `key` or similar parameter), or that names no host.

An airlock pod with a problem refuses to start. The command that checks a release before it is applied is in
[Validate the documents](release.md#validate-the-documents); what the controller checks on top is in
[What the controller checks](release.md#what-the-controller-checks).
