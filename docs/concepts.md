# Concepts

AIsolate gives an organisation's own chat, agent or application a governed endpoint to a large language
model over the organisation's existing databases. That endpoint is an **airlock**. The model receives only the
data the person asking may see, returns nothing but text, and each airlock runs in its own sealed pod
pair that reaches nothing but its model, its data sources, its own MCP server and the identity provider.

## Who decides what

| Layer | Decides | Written by | Held by |
|---|---|---|---|
| Identity | who may ask an airlock at all | the identity provider's admin (the accounts and their roles); the space's and the airlock's `access.roles`; the airlock's `access.callers` (OAuth clients) and `access.clients` (machine clients) | the airlock pod, and its tool server again |
| Rules | which rows and columns of an entity a user reads, and which aggregates | the developer, as rule sets in the version's code | the MCP server, compiled into parameterised SQL |
| Code | which tools, context preparators, memory handlers and verifiers exist | the developer, in the version's code and its `aisolate.yaml` | the version's two pods, which load only its code, pinned by digest |
| Network | what a pod can reach | the admin (`platform.egress.hosts`); the controller, from the documents and that map | one default-deny NetworkPolicy per pod |

Identity decides which airlocks a user sees, the rules decide which rows, the code decides which tools run. A
user's roles admit them to an airlock and select the rules; the airlock lists the roles it admits.

## Vocabulary

| Term | Meaning |
|---|---|
| Space | One team's or product's container: its developers (members who edit it, by e-mail), the roles that may ask every airlock of it, and the LLMs and data sources its airlocks share beside the global ones. The admin creates it and names its developers on its form; either list may be empty. |
| Airlock | A named assistant in a space; its id and its space's name its endpoint path. It lists who may ask its versions besides the space's roles: its own roles (maybe none), its callers and its machine clients (maybe none), and holds the LLMs and data sources its versions share. One `Airlock` resource naming its space (`spec.space`); it owns no pod. |
| Version | What runs: a name (`v1`, `pilot`) and a description; Pre-LLM (context preparators, conversation memory); LLM (the main LLM by id, prompt, tools); Post-LLM (answer verifiers); LLMs of its own, without a key; and the code. It has no access and no data sources of its own: its space's and its airlock's roles, and its airlock's callers and machine clients admit; its code reads its airlock's, its space's and the global data sources. One `AirlockVersion` resource naming its airlock (`spec.airlock`), `<airlock>-<version>`, each with its own endpoint and its own pod pair. Messages write it `<space>/<airlock>/<version>`: `hr-space/hr-contracts/v1`. |
| Code | One version's tools, handlers and rule sets, built from one project and brought only by `aisolate deploy`, pinned by its digest. Every class of the project belongs to that version. The AIsolate UI shows the code's tools and handlers read-only. |
| Roles | The role names a space lists in `access.roles` and those an airlock lists in its own `access.roles`, either list maybe empty. A space's roles may ask every airlock of the space; an airlock's own roles may ask that airlock only. The token's roles claim must hold one of the two lists' roles, exactly; the identity provider decides who holds them. |
| Developers | The e-mails a space lists as its members: they edit the space and its airlocks in the AIsolate UI. A developer is no asker: to ask an airlock, they need one of its roles like anyone. |
| Callers | The OAuth clients an airlock admits in `access.callers`, by the token's `azp`; `*` admits any client registered at the identity provider. A space lists no callers. |
| Machine clients | The OAuth clients an airlock admits in `access.clients` without a user: a token with no `email` whose `azp` is exactly one of them is admitted, the callers not consulted; `*` is no wildcard. A workflow tool asks this way, as itself ([In production](integrate.md#in-production)). Its exchanges are audited with no user and its client id. A space lists none. |
| Exchange | One request and its answer. |
| Audit record | The one JSON line the airlock pod writes for every exchange and every refused request, kept by AIsolate's backend in an append-only, hash-chained table (and printed to stdout for a SIEM): who asked what (a machine client by its client id), the outcome, the tools called with their row counts, the evidence ids, the verdicts, the model's tokens and the duration. See [Audit](audit.md) and [Audit trail](security.md#audit-trail). |
| Stage | Where in an exchange a handler or a tool runs: Pre-LLM, LLM or Post-LLM. See [Stages](#stages). |
| LLM | A model a version may use, by id: `{id, provider, url, model}`, the provider `ollama` or `openai` (an OpenAI-compatible server, with an optional API key kept as a Secret). It has no type: the same entry chats or embeds as far as its model can. Defined globally by the admin, on the space, the airlock or the version ([Where LLMs and data sources are defined](#where-llms-and-data-sources-are-defined)). A version names its main LLM, the one that answers, by id (`llm: granite`); its code names any other (`aisolate.llm("nomic")`). |
| Data source | A database the version's code reads, by id: a URL and a Secret with the user and password of its role. A tool plugin names the one its entities live in; a handler asks for one by id. Defined globally by the admin, on the space or the airlock, never on a version: a login belongs to an owner that outlives one version. |
| Context preparator | A `ContextPreparator` handler of the version's code, listed by the version. It reads its data source and adds passages to the model's context before the model is asked. |
| Egress map | The admin's `platform.egress.hosts`: each host a URL of the global level, a space, an airlock or a version may name, with its NetworkPolicy peer. |
| Pod pair | Per version: the airlock pod `<space>-<airlock>-<version>-airlock` and its MCP server `<space>-<airlock>-<version>-mcp-server`. |
| Revision | Twelve hex digits of the SHA-256 of the documents one version serves. See [Names, ids and revision](documents.md#names-ids-and-revision). |
| Token | The signed JWT the identity provider issues at login. Every AIsolate process validates it itself. |
| Obligations | What the rules yield for one user: a row filter, hidden and masked columns, a minimum group size. |
| Fail closed | When a check cannot run, the answer is "no". |

## Where LLMs and data sources are defined

Four levels define LLMs, and a version sees all four: the admin's global ones, its space's, its airlock's and
its own. Data sources stop at the airlock: a version defines none. The code, the prompt, the tools and the main
LLM are the version's alone.

| Level | Document | Defined by | Also carries |
|---|---|---|---|
| global | `Global` (one per install, `aisolate`) | the admin, on the LLMs and Data sources pages | nothing else |
| space | `Space` | the admin | `access.roles`, who may ask every airlock of the space |
| airlock | `Airlock` | the admin, a developer of the space, the project's `Airlock` document | `space`, `access.roles` (who may ask it besides the space's roles; need not be on the space), `access.callers`, `access.clients`, `description` |
| version | `AirlockVersion` | the project's `aisolate.yaml` | `airlock`, `version`, `description`, the three stages, `llm` (the main LLM's id), `prompt`, `tools`, `jar.digest`, `jar.tools`; `llms` without an API key, no `dataSources` |

- Each level lists `llms` (`{id, provider, url, model}`) and `dataSources` (`{id, url, secretRef}`), each id once.
- The same id at a nearer level replaces the farther one whole: version, then airlock, then space, then global.
  Other ids add up: a version's catalog is the union, sorted by id. LLM ids and data source ids are separate
  namespaces.
- Every space inherits every global entry, and any developer may read them: a global data source is open to every
  space's code.
- What must hold is the result: the version's `llm` resolves, and every data source its code names does.

## Stages

An exchange runs in three stages, and what a version runs is defined in that order: the three stages; the
databases its code reads are its airlock's, its space's or the install's `dataSources`. The version's Overview
follows the same order. Who may ask is not part of it: the airlock's access says.

| Stage | Group | Runs | Code folder of the template |
|---|---|---|---|
| Pre-LLM | `preLLM` | the context preparators, which read their data source and add passages as evidence; the conversation memory | `pre_llm/` |
| LLM | `llm`, `prompt`, `tools` | the main LLM with its prompt, and the tools it may call, each over its plugin's data source; its answer comes in AIsolate's answer format, every figure a reference AIsolate writes in and checks ([Verification](verification.md)) | `tools/` |
| Post-LLM | `postLLM` | the answer verifiers, which annotate and never block | `post_llm/` |

The folders are a convention of the template; AIsolate reads a class's stage from the interface it implements.

## Roles

| Role | Granted by | Does |
|---|---|---|
| Admin | realm role `aisolate-admin` | Everything, in every space. Defines the global LLMs and data sources. Creates a space, edits its name and description, its developers and roles, its LLMs and its data sources. Installs AIsolate and chooses `headless`. Keeps the egress map. Releases documents to a headless install, with the Secrets they name. Resets the install (`DELETE /spaces` removes every space with its airlocks). |
| Developer | the space: a member of it, named by e-mail | In that space only: writes the project (tools, handlers, rule sets, the airlock's document), deploys it (`aisolate deploy`), creates and edits its airlocks (their roles, callers, LLMs and data sources), tries them in a chat. Sees the space's own LLMs and data sources and the global ones read-only, and the audit of their spaces. The admin types the e-mail; in another space a developer is refused with a 403 that names the space and says to ask its admin. |
| Asker | a role the organisation's identity provider gives them, which a space or an airlock lists | Asks, through the organisation's chat, the airlocks whose space or which itself lists one of their roles, as far as the rules their roles select allow. Sees no space in the AIsolate UI. |
| Auditor | realm role `aisolate-auditor` | Reads the audit trail in the AIsolate UI's **Audit** page and through the API, and exports its report; nothing else ([Audit](audit.md)). |

Roles add up: an airlock admits the roles of its space and its own, and the rules still decide which rows anyone
sees. The operators' realm holds two AIsolate roles, the admin's and the auditor's; they open the UI and admit nobody to an
airlock. The organisation's own roles (`employee`, `ceo`, `founder`) are what a space or an airlock lists and what the
rules select on. The identity provider authenticates the people and holds their roles.

## Components

| Component | Runs as | Does |
|---|---|---|
| Customer's chat | The organisation's app; `apps/chat` is the sample | Signs the user in with PKCE, discovers the airlocks, posts questions with the user's token, renders the answer and its evidence. |
| Airlock pod | `aisolate/airlock`, one per version | The version's endpoint. Validates the token, admits the user who holds a role the space or the airlock lists and the client the airlock lists, or a machine client the airlock lists, runs the version's handlers and the agent loop, streams the answer. Serves its version only. |
| MCP server | `aisolate/tool-server`, one per version | Publishes the version's tools. Validates the token again, evaluates the rules, compiles them into one SELECT on the tool's data source, returns evidence. Reachable from its own airlock pod only. |
| Controller | `aisolate/controller`, one per install | Watches `Space`, `Airlock` and `AirlockVersion` and renders the pod pair, Services, routes, policies, the documents' ConfigMap and the token Secrets of every valid version. Reports status. |
| Backend | `aisolate/backend`, with the AIsolate UI only | The UI's API and the CLI's endpoint. Keeps spaces, airlocks, versions and their code in its own PostgreSQL, applies them to the cluster after every write, keeps credentials in Secrets. |
| AIsolate UI | Next.js, with `headless=false` only | Where admins define the global LLMs and data sources, and developers and admins spaces with their developers and roles, LLMs and data sources, and airlocks with LLMs and data sources of their own and their versions, and see each version's code. |
| CLI and SDK | the `aisolate` binary; `libs/sdk` | `create`, `login`, `logout`, `build` and `deploy`. The SDK is what a project compiles against. |
| Chart | Helm, the chart in [`aisolate-chart`](https://github.com/reportix/aisolate-chart) | Installs the CRDs, the controller, the policies, the release Service and routes and the egress proxy; with `headless=false` the UI, the backend and its database. |
| Identity provider | The organisation's, or two: the operators' and the application users' | Authenticates the users, issues tokens for the audience `aisolate`, publishes JWKS. |
| Customer databases | The organisation's PostgreSQL | The data sources: the tables the MCP server reads for the tools, the documents the context preparators search. |
| Model | Ollama | Receives text, tool schemas and the rows the user may see. Never a token, a name or a role. |

Nobody types a server URL: the controller names each version's MCP server and hands its address to its
airlock pod. The MCP server is AIsolate's; the tools inside it are the developer's plugins,
written against the SDK.

## With or without the AIsolate UI

`headless` is the chart's one switch. Every install runs the same pods from the same controller; only who
applies the documents and creates the Secrets differs. With the AIsolate UI (`headless=false`), the backend
applies every save. Headless, an admin applies the documents through the organisation's own pipeline, and no UI
and no backend run.

| Thing | With the AIsolate UI | Headless |
|---|---|---|
| Spaces, airlocks, versions | the backend's PostgreSQL, applied as resources after every write | documents in the organisation's deployment repository, applied by its pipeline |
| Code | built by `aisolate deploy`, stored by the backend; each pod fetches it at start and checks its digest | none: a version with code needs the backend, so headless runs versions without code only |
| Credentials | the user and password become a Secret the backend creates, owned by the `Space` or `Airlock` that defines the data source | the admin creates the Secret the `secretRef` names |
