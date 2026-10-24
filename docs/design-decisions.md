# Design decisions

Why AIsolate is built the way it is: each decision, what it answers, and what follows from it. The guarantees
they add up to are in the [README](../README.md#what-aisolate-guarantees); where each is enforced is
[Security](security.md).

## The model is never an enforcement point

**Question.** A language model can be talked into anything. Where does access control live when a model is in
the loop?

**Decision.** Nowhere near the model. It receives text, tool schemas and the rows the asking user may see, and
returns text. It never receives a token, a name or a role, and it executes nothing: a tool call is a request the
airlock pod and the MCP server judge with the user's token, not an action the model performs.

**Consequence.** A prompt injection can steer what the model asks for, never what it gets. The agent loop has no
way to reach the token (an ArchUnit rule keeps the `chat` package away from Spring Security and `libs/identity`),
so there is nothing for the model to leak.

## Rules become SQL at the data boundary

**Question.** Who decides which rows a user reads, and where is the decision applied so that no other path exists?

**Decision.** The developer writes rule sets beside the code (allow and deny, row filters, hidden and masked
columns, a minimum group size); the MCP server evaluates them for the asking user and compiles the obligations,
together with the model's validated arguments, into one parameterised SELECT on an allow-list catalog of
entities and columns, run under the data source's read-only role. The model's filters can only narrow that
statement; it writes no SQL.

**Consequence.** Default deny, an explicit deny wins over any allow, a column the catalog lacks is an argument
error and never a statement. The same compiler runs in both runtimes and the conformance cases pin the exact
statement and parameters, so a divergence is a failing build, not a surprise in production.

## Deny is silent

**Question.** What does a user learn from a refusal?

**Decision.** Nothing. An excluded row looks like a missing row: the tool returns zero rows and no error the
model could read, and when every evidence is empty the answer is exactly "I don't know". A 403 exists only
before any data access, for a user the airlock does not admit at all.

**Consequence.** A user cannot probe the shape of what they may not see, and the model cannot be made to explain
a refusal it never saw. The cost is deliberate: a misconfigured rule looks like missing data, so the audit line
records every tool call with its row count for the developer to read.

## One pod pair per airlock, default deny around it

**Question.** The guarantees are application code. What holds when the code does not, because of a hostile
project, a steered model or a vulnerable dependency?

**Decision.** Each airlock runs as its own pod pair, the airlock pod and its MCP server, in a namespace with a
default-deny NetworkPolicy and one policy per pod. A pod reaches its model, its data sources, its own MCP
server, the identity provider, the backend's ingest port and the trace collector, and nothing else; every other
host goes through an egress proxy by exact name or not at all. Images are pinned by digest, the root filesystem
is read-only, the process is non-root with no capabilities and no ServiceAccount token; the airlock pods may run
under a sandboxed runtime class.

**Consequence.** A beaten process reaches only what its own pod may reach; two airlocks share nothing, not even
a Secret. `helm test` proves the pods unreachable except through the ingress. The isolation is as real as the
cluster's CNI, which the install documentation says plainly.

## One token, validated at every hop, never exchanged

**Question.** How does the application user's identity reach the database boundary without AIsolate becoming an
identity provider or a trusted middleman?

**Decision.** AIsolate ships no identity provider and no SDK of one. The organisation's provider issues a JWT
for the audience `aisolate`; the airlock pod validates it against the issuer's JWKS, forwards it unchanged to
the MCP server on every call, and the MCP server validates it again, independently. The backend does the same
for the UI and the CLI. Nowhere may `org.keycloak` be imported.

**Consequence.** A compromised airlock pod cannot invent a user; it can only replay the tokens it holds for their
remaining minutes. People live in the identity provider alone: AIsolate never creates, invites or deletes one.

## Who may ask is a list of e-mails; roles only select rules

**Superseded 2026-10-24** by [Who may ask is a set of roles](#who-may-ask-is-a-set-of-roles).

**Question.** What admits a person to an airlock, and what decides what they see once in?

**Decision.** Two separate things. Admission is a list: the space's users may ask every airlock of it, an
airlock's own users may ask that airlock alone, and the calling client must be one the airlock lists (`azp`), or,
for a token without an e-mail, one of its machine clients. The token's roles never admit anyone; they only select
which rules apply.

**Consequence.** A new role in the identity provider changes no one's reach; a developer sees exactly who may
ask by reading two lists. A user the airlock admits whose roles match no rule gets zero rows and never an error.

## Who may ask is a set of roles

**Question.** Who keeps the list of askers: AIsolate or the organisation's identity provider?

**Decision.** An airlock admits a token that holds one of the roles its space or it lists (exact, case-sensitive,
`*` is no role) and whose `azp` the airlock lists; a machine client is admitted by `access.clients` alone. The
roles come from the identity provider, in the token's roles claim. The e-mail still feeds row filters and the audit
line. A space's developers are e-mails typed in the UI: they edit, and ask like anyone, by a role.

**Consequence.** Who holds a role is the identity provider's to change, with no AIsolate list to keep in step and no
directory to search. An airlock with no roles admits nobody, so an upgrade fails closed until an admin sets them.
A role is a name both sides must agree on: a typo admits no one rather than the wrong one.

## Context is the developer's code, pinned by digest

**Question.** AIsolate promises to store no business data and run no retriever of its own. Where do passages,
memory and domain checks come from?

**Decision.** From the project. Its classes implement the SDK's `ContextPreparator`, `ConversationMemory`,
`AnswerVerifier` and `ToolPlugin`; nothing is registered, the pods scan the code and find them. The code is one
artifact per airlock, a jar or a Python zip with its vendored dependencies, kept by the backend and named in the
`Airlock` by its `sha256`. Each pod fetches it at start with a token for that airlock alone, hashes the bytes as
they arrive and refuses to start on any other digest.

**Consequence.** What runs is exactly what was deployed, and only that airlock's pods can fetch it. The one place
a project's Java code runs outside its pods is the backend's inspection at deploy; a Python zip is read by its
manifest and never imported there.

## Figures are written in, never typed

**Question.** A model that answers in prose can misquote a number it just read. How is an answer tied to the
evidence it came from?

**Decision.** The model's final turn is one JSON object in AIsolate's answer format: text with placeholders, and
for each placeholder what it refers to, a cell, a computation over cells, a table, a quote. AIsolate writes each
value in from the evidence, formats it with its unit, and checks whom, which period and which unit the model
says it is about. A number or a marked fact the model typed itself is shown as unsourced. The checks are
deterministic: no model is asked.

**Consequence.** Every figure in an answer carries its provenance to the user and to the audit line (sourced,
computed, unsourced, mismatch), and a masked or hidden value can never become one. What this proves and what it
does not is written down ([Verification](verification.md#what-is-proven-and-what-is-not)).

## The audit trail is part of the platform

**Question.** An organisation that lets a model near its data must be able to show, later, who asked what and
what came back. Whose job is that?

**Decision.** AIsolate's. The airlock pod writes one JSON line per exchange and per refusal, buffers it on
disk, and hands it to the backend with a token derived for that airlock alone; the backend keeps it in an
append-only table, hash-chained per airlock, that a database trigger refuses to update, delete or truncate.
When the buffer is full the pod refuses new questions rather than answer off the record. Each line is also
printed to stdout for a SIEM, and one exchange exports as PDF, JSON or an XBRL instance of the AIsolate Audit
taxonomy.

**Consequence.** The question and the answer exist in exactly one place in AIsolate, and a tampered or removed
line is found by walking the chain. The trail proves what the airlock saw and said; it cannot prove what the
database held at the time, and the documentation says so.

## The airlock is what runs

**Question.** Does an airlock have versions, environments, or a draft?

**Decision.** No. One `Airlock` document is one endpoint and one pod pair; a deploy updates it in place, and a
change rolls its pods. Its revision is the hash of the documents it serves, carried by every response and every
MCP call, so a tool server answers only for the revision it loaded.

**Consequence.** There is one thing to point a chat at, one thing to audit, and one thing to roll back by
re-applying the previous documents. A team that wants a second airlock creates one.

## LLMs and data sources by id, at three levels, with no credential in a project

**Question.** Where do a model's URL and a database's password live, and how does a project name them without
holding them?

**Decision.** The admin defines LLMs and data sources for the install, a space for its airlocks, an airlock for
itself; each is an id, a URL and a Secret reference, and the nearest definition of an id wins. A typed
credential is sealed in the backend's database and mirrored into the Secret the pods read; an admin may reveal
it after a fresh sign-in, every time recorded. A project names
them by id in `aisolate.yaml` and in its code and holds no credential. An LLM has no type: one entry chats or
embeds as its model can.

**Consequence.** The same project deploys to another install unchanged; a password rotates in one Secret; a
developer never reads it through the API (a Java deploy runs code in the backend, which holds the key, so
until deploys stop doing that a developer who deploys Java can decrypt every credential), and an admin who forgot one recovers it instead of resetting the data source.
The cost is a master key in the backend: whoever holds it and the database holds every credential. The controller renders the resolved catalog into each pod's environment, so the pods
resolve nothing themselves.

## Documents are the interface; the UI is optional

**Question.** Must an organisation adopt a new UI to run AIsolate?

**Decision.** No. The `Global`, `Space` and `Airlock` documents are the whole interface between defining and
running; the same controller renders the same pods whoever applies them. With `headless=false` the backend and
the AIsolate UI write them after every save; headless, the organisation's own pipeline does, and no backend runs.

**Consequence.** An install fits a GitOps repository as it fits the UI. The one thing headless forgoes is
code: an airlock's artifact lives in the backend, so a headless airlock runs without one.

## Two runtimes, one contract, Python by default

**Question.** The platform is Java. Developers who write tools and handlers are, more often, not.

**Decision.** A project is Python by default, Java on request, with the same four templates in each. Each
language has its own airlock and tool-server images, built on the same libraries' contract and rendered by the
controller into the same pod shape. The behaviour that matters, admission, rules, SQL, grounding, the audit line,
is written down once as conformance cases, run by both runtimes in every build; the Java runtime is the
reference.

**Consequence.** A developer's project is a plain Python package or a plain Gradle project. A difference between
the runtimes is a failing build. The Python pods never see the Java pods' code and vice versa; what they share
is the contract and the cases.

## Permissive dependencies only

**Question.** AIsolate is AGPL-3.0-only with a commercial licence beside it. What may it depend on?

**Decision.** Apache, MIT, BSD, PSF, ISC and OFL only; nothing GPL, LGPL, AGPL or MPL, however convenient. Hence
pg8000 rather than psycopg, httpx2 rather than requests, a twenty-line OTLP exporter rather than the one that
pulls `requests`, a licence check in the Python image build, and no dependency for what a few lines do.

**Consequence.** Every runtime image can be shipped under either licence. Adding a package means checking its
whole tree first, and the documentation says how.

## Three packages per app, kept apart by tests

**Question.** How does a security property survive the next hundred changes?

**Decision.** Each Spring Boot app is `application`, `domain` and `infrastructure`, and an ArchUnit test in each
pins the boundaries that matter: the airlock's chat code cannot see the token, the airlock pod has no database
and no write endpoint, the backend has no model client, every backend endpoint names who may call it. A
security property gets a test that fails when it breaks; a fix without one is not finished.

**Consequence.** The build, not a reviewer, notices when the agent loop acquires a way to the token. The
conformance cases do the same across languages.

## Traces hold names and counts, never content

**Question.** Observability wants to see inside an exchange; the guarantees forbid most of what is inside.

**Decision.** One trace per exchange, exported over OTLP, whose spans carry the airlock, the revision, the tools
called, the row counts, the figures by provenance and the timings; never a question, an answer, a row, a figure's
value, a statement, a token, an e-mail or a role. The question and the answer are in the audit line only.

**Consequence.** Traces and metrics can go to any backend the organisation already runs, and the AIsolate UI's
Monitoring reads them back through the backend, never from the browser.

## Two identity domains: operators and application users

**Question.** One realm meant one browser session for the dashboard and the chat, and the organisation's identity provider is not where AIsolate's own admins live. Who signs in where?

**Decision.** The pods (airlock, MCP server, discovery) trust `platform.identity`, the organisation's identity provider, where the application users are. The backend, and through it the UI and the CLI, trust `platform.identity.operators`, which defaults to the same provider, so a one-realm install is unchanged. The release host's discovery document is the pods', the application users'; the backend host's is the operators'. In UI mode the release host's `/.well-known/` answers 503 until the first space exists: the `Space`s own the discovery pods.

**Consequence.** With two providers, the dashboard and the chat keep independent sessions and an operator's token is refused by the pods and the reverse; two egress peers are possible (`platform.identity.operators.egress`). One token per hop, validated unchanged, stays: no exchange, no new validation code.

## Read next

- [Architecture](architecture.md): the modules and the pods these decisions shaped.
- [Security](security.md): one request end to end, the gates, the residual risks.
- [Concepts](concepts.md): the vocabulary.
