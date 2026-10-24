# API

Every path sits under `/v1`. On the release host, `/airlocks` and each airlock's
`/spaces/{space}/airlocks/{airlock}/chat` are what an end user's chat sees, served by the
airlock pods. On the backend's host, `/spaces` is the AIsolate UI's API, served by the backend, with the
AIsolate UI only. Every response from an airlock pod carries `x-aisolate-revision`.

## Connect a client

A chat, agent or application connects with nothing configured but AIsolate's URL:

1. Call AIsolate without a token. The 401 carries
   `WWW-Authenticate: Bearer resource_metadata="<host>/.well-known/oauth-protected-resource"` (RFC 9728). With
   the AIsolate UI installed, the backend serves that document on the release host too, so a client can sign
   in before the first deploy.
2. The document names the identity provider (`authorization_servers`) and the scope to request (`aisolate`,
   which carries the audience).
3. Read the provider's metadata (RFC 8414), register the client (RFC 7591; confirm it before the next sign-in and
   register again if the provider forgot it, RFC 7592), and sign the user in with Authorization Code and PKCE.
   Keep the token in memory. A provider may refuse browser-originated registrations; the sample chat forwards
   the registration through its own server route.
4. Send the token on every call. `GET /v1/airlocks` lists the airlocks this user may use, in every space, with
   the endpoint it may post to. A client names the airlock it talks to.

Nobody types a username or password into the client, and nobody configures the identity provider in it. An
airlock that admits any registered client lists `*` in `access.callers`; identity still comes from the token,
the token must hold a role the space or the airlock lists, and the roles also select the rules that
bound the data. A machine client that asks as itself has no e-mail: its token is admitted when its `azp` is one
of the airlock's `access.clients`.

A workflow tool that cannot read a stream asks for the whole exchange as one JSON document: [Call an airlock
from your workflow](integrate.md).

## Endpoints

| Method and path | Served by | Caller | Purpose |
|---|---|---|---|
| `POST /spaces/{space}/airlocks/{airlock}/chat` | that airlock's pod | a user holding a role its space or the airlock itself lists, from a caller the airlock lists; or a machine client the airlock lists | The airlock's endpoint, below |
| `GET /airlocks` | the discovery pods, through the release Service | any valid token | The airlocks of every space that admit this user (by a role of their space or their own) and this client, or this machine client, each with its space's id, its description, the endpoint it may post to and its tools (none without code), sorted by name, digit runs as numbers: `{id, space, name, description, endpoint, tools}` |
| `GET /.well-known/oauth-protected-resource` (no prefix) | the discovery pods and every airlock pod; the backend too | anyone | RFC 9728 metadata naming the identity provider and the `aisolate` scope |
| `GET /spaces` | backend | any valid token | The spaces the caller may open: every space for the admin, those they develop for a developer, none for anyone else (200, `[]`) |
| `GET /llms`, `GET /llms/{id}` | backend | admin, developer of any space | The global LLMs, every space's unless a nearer level defines the same id: `[{id, provider, url, model, secret_ref, updated_at}]`, sorted by id, `secret_ref` naming the Secret of an OpenAI-compatible one's API key, null without; one, or 404 `no model named granite` |
| `POST /llms`, `PUT /llms/{id}`, `DELETE /llms/{id}` | backend | admin | Create `{id, provider, url, model, api_key}` (409 `id: a model granite already exists`), `provider` `ollama` or `openai` and `api_key` optional and write-only (an admin may reveal it), an `openai` one's, which becomes the Secret `aisolate-llm-<id>-<6 chars>`; edit its `provider`, `url`, `model` and `api_key`, the id fixed, the stored key kept while the `url` stays; delete it (204, its Secret with it), 400 when an airlock would lose its main LLM ([LLMs and data sources](llms-and-data-sources.md#api)) |
| `GET /data-sources`, `GET /data-sources/{id}` | backend | admin, developer of any space | The global data sources: `[{id, url, secret_ref, updated_at}]`, sorted by id; one, or 404 |
| `POST /data-sources`, `PUT /data-sources/{id}`, `DELETE /data-sources/{id}` | backend | admin | Rows `{id, url, username, password}` as a space's data sources, the Secret `aisolate-ds-<id>-<6 chars>`; 409 `id: a data source hr already exists`; a delete is 400 when an airlock's code reads it and nothing nearer defines it |
| `POST /credentials/{secretRef}/reveal` | backend | admin, signed in within five minutes | A stored API key or data source login in clear, `{values: {apiKey}}` or `{values: {username, password}}`, `Cache-Control: no-store`; every call is recorded ([below](#post-v1credentialssecretrefreveal)) |
| `GET /spaces/{space}` | backend | admin, developer of the space | The space with its `developers` (e-mails) and `roles` (the roles that may ask every airlock of it), its own `llms` and `data_sources`, and `caller_role`, what the caller may do there: `admin` or `developer` |
| `POST /spaces`, `PUT /spaces/{space}` | backend | admin | Create a space, with no developer yet if need be; edit its name, description, developers, roles, LLMs and data sources |
| `DELETE /spaces` | backend | admin | Every space with its airlocks and their code, the rows in one transaction; then, with a cluster, their resources and Secrets, waiting up to 45 s for the pods to go. No confirmation and no undo. Used by `aisolate-reset`; create a space and an airlock again before the next deploy |
| `POST /spaces/{space}/deploy` | backend | admin, developer of the space | The CLI's one call, below |
| `GET /spaces/{space}/airlocks`, `GET /spaces/{space}/airlocks/{airlock}` | backend | admin, developer of the space | The airlocks, by name, digit runs as numbers; each with its roles, callers, `clients` (its machine clients), endpoint, revision, form (`llm`, `prompt`, `context_preparators`, `memory_handlers`, `tools`, `verifiers`, `llms`, `data_sources`), its `main_llm: {id, provider, url, model, from}` (`from` `airlock`, `space` or `global`; `null` when `llm` resolves to nothing) and code: `jar: {digest, size, tools, handlers, rules}`, `null` without code |
| `POST /spaces/{space}/airlocks`, `PUT /spaces/{space}/airlocks/{airlock}` | backend | admin, developer of the space | Create an airlock with its name, description, roles, callers, clients, LLMs and data sources (the id is generated, or pinned with `id`); edit them in place, which bumps its revision, one more on every save or deploy: a counter, not the release [revision](concepts.md#vocabulary) in `x-aisolate-revision`, twelve hex digits of the SHA-256 of its documents. An airlock made here has no code, so it lists no tools and no handlers until a deploy brings them, with its `llm` and `prompt`; a save keeps what the last deploy brought |
| `GET /audit/events`, `GET /audit/events/{id}`, `GET /audit/events/{id}/export`, `GET /audit/export`, `GET /audit/verify`, `GET /audit/data-error` | backend | admin, auditor; a developer for the spaces they develop | The audit trail: the exchanges and refusals, newest first; one with all it recorded; all a search finds, or one event, as a PDF, JSON or XBRL file; whether an airlock's chain is intact; the data source an airlock could not read ([The audit endpoints](#the-audit-endpoints)) |
| `GET /monitoring` | backend | admin, developer of any space | Which of Monitoring's views the install has: `{tracing, metrics}`, each `true` when its store is configured; the AIsolate UI shows the rail item and a tab per view |
| `GET /metrics?space=&airlock=&period=` | backend, with a metrics store configured (404 otherwise) | admin; a developer for the spaces they develop | Monitoring's charts ([The metrics endpoint](#the-metrics-endpoint)) |
| `GET /traces`, `GET /traces/{trace_id}` | backend, with a trace store configured (404 otherwise) | admin; a developer for the spaces they develop | Monitoring's traces: the exchanges, newest first; one exchange's audit line and its steps from the trace store ([The trace endpoints](#the-trace-endpoints)) |
| `POST /audit/events` | backend, its ingest port 8085 only | an airlock pod, with its airlock's ingest token | One audit line in, appended to its airlock's chain ([The audit endpoints](#the-audit-endpoints)) |
| `GET /code/{space}/{airlock}/{digest}` | backend, its ingest port 8085 only | the airlock's two pods, with its code token | The airlock's artifact at start ([The code fetch](#the-code-fetch)) |
| `GET /swagger-ui.html`, `GET /v3/api-docs` | backend | anyone, when `AISOLATE_API_DOCS=true` (off by default) | The OpenAPI documentation; operations need the token |

A space's and an airlock's `name` is its label, optional: a blank or missing one, on create or on a `PUT`, is
stored as the id, so every list, header and discovery answer shows the id instead.

A caller who is neither the admin nor a developer of the space gets 403 with `detail` naming it: `You are not a
developer of space acme: ask its admin to add you as a developer.`; the other refusals' 403 says `forbidden`. A
space's developers and roles are the admin's: a space form's `developers` is a list of e-mails (one without an `@`,
blank or repeated, case ignored, is 400: `developers: eric is no e-mail`) and its `roles` a list of role names. The
identity provider holds who has a role; there is no user list and no directory to search.

Who may ask an airlock is the holders of its space's roles and of its own (see [Roles](concepts.md#vocabulary)); a developer asks like anyone, by a role. A create form that
sends no `roles` starts the airlock with no roles of its own, and one that sends no `callers` with `*`. An
airlock's own roles need not be the space's, and either list may be empty: an airlock nobody may ask is allowed.
A role is a non-blank name with no whitespace, listed once; `*` is 400 (`roles: * is no role: list each role; only callers take * for any`).
A `PUT` that leaves out `roles` clears the airlock's own roles; `roles` may be empty, an empty `callers` is 400 (`callers: must name at least
one calling client`). `clients`, the machine clients admitted on a token without an e-mail, may be empty and
are client ids matched exactly: a blank, repeated or spaced one, or `*`, is 400 (`clients: * is no wildcard: list
each client id`). A `PUT` that leaves out `clients` clears them too; the AIsolate UI has no field for them and
sends them back unchanged on every save.
Removing a role from a space never touches its airlocks. An airlock is created with a name and a description alone;
its `llm` and `prompt` come with `aisolate deploy`, and until both are there it runs nothing. An airlock names the LLM that
answers by id (`llm`), resolved, once set, at every create, save and deploy: `llm: no model named granite in this airlock's space or globally`.

An LLM (a model, in the UI) is `{id, provider, url, model}` and has no type: the same entry chats or embeds as far
as its model can. `provider` is `ollama` or `openai` (an OpenAI-compatible server, its `url` the API's base with
its version, `https://api.openai.com/v1`). An `openai` row of a space, an airlock or the install may carry an API key:
`api_key` is write-only, as a data source row's `password`: no response carries it, and it becomes a Secret the row
names in `secret_ref`, sealed beside it in the backend's database for an admin to reveal;
a row keeps it while its `url` stays (`llms[0].api_key: required again with a new url` otherwise), an `ollama`
row names none (`llms[0].api_key: an Ollama model takes no API key`), and a project's rows take none.
The admin's global ones, a space's and an airlock's own (`llms`) are one catalog by id, the nearest
replacing the farther whole; data sources likewise. A space's or an airlock's GET returns its own rows only: the
inherited ones are `GET /llms`, `GET /data-sources` and the space's. An entry is refused on its field:
`llms[1].id: granite is listed twice`, `llms[0].provider: bedrock is not one of [ollama, openai]`, `llms[0].model:
required`, and the URL rules' messages on `llms[i].url`.

With a cluster, every create and save of a space, an airlock, a global LLM or a global data source
(which applies the install's one `Global`) is applied to it before the answer,
which says in the header `AIsolate-Cluster` whether the cluster took it: `applied`, or `not applied` (the
backend log says why; the backend applies every space again every five minutes). A `PUT` may send `If-Match`
with the `updated_at` (a space) or the `revision` (an airlock) its form was read at: when another
save came between, it is refused with 409 `updated_at: Saved by someone else since you opened it; reload it and
save again.` (`revision: …` for an airlock), so a stale tab cannot give a removed user access back. Without the
header the last save wins.

A save of a space or an airlock, or a delete of a global LLM or data source, that would leave an airlock
without what it resolves today, its main LLM or a data source its code reads, is 400: the controller would hold
that airlock back and it would serve no one. The message names the airlocks and where to define it instead, for
example `llms: acme/hr and acme/sales would have no model granite; define it on the airlock first`, and for a
global delete `id: acme/hr would have no model granite; define it on its space first`. Define an LLM nearer before
removing it farther out.

Every airlock's pods are named `<space>-<airlock>-mcp-server` and similar, at most 63 characters, as
Kubernetes allows: an airlock id that leaves no room is 400, naming the length (`id: it
would run as …, 64 characters; Kubernetes allows 63 (shorten the space or the airlock id)`). An id
derived from the name is cut to fit.

The AIsolate UI's forms read and write the data sources as rows `{id, url, secret_ref, username,
password}`: `username` and `password` are write-only, no response carries them, and they become a Secret, `secret_ref` names it,
sealed beside it for an admin to reveal. With a
cluster, a row keeps only the `secret_ref` its id already had, and only while its `url` stays; a new URL needs
the password again (`data_sources[i].password: required again with a new url`). The code of an airlock reaches
it only through the deploy call.

### `POST /v1/credentials/{secretRef}/reveal`

An admin reads back a stored API key or data source login, named by the `secret_ref` a row carries. No body.

```
POST /v1/credentials/aisolate-ds-hr-k3x9qa/reveal
Authorization: Bearer <an admin's token>
```

200 `{"values": {"username": "…", "password": "…"}}` (`{"values": {"apiKey": "…"}}` for a model), with
`Cache-Control: no-store`. The token needs the realm role `aisolate-admin` and an `auth_time` no older than five
minutes (`AISOLATE_CREDENTIALS_REVEAL_MAX_AGE`, `PT5M`), and a token from a script's or the CLI's direct grant has
none: the AIsolate UI is the way. Refusals, checked in this order, each recorded in `credential_events` with a
`reason` that the problem document carries too:

| Status | `detail` | `reason` |
|---|---|---|
| 404 | `unknown credential`: no row of any level names the Secret | `unknown` |
| 403 | `not from the AIsolate UI`: the token's `azp` is not one of `AISOLATE_CREDENTIALS_REVEAL_CLIENTS` (default `aisolate-ui`), or absent | `wrong_client` |
| 403 | `the identity provider sends no auth_time claim` | `no_auth_time` |
| 403 | `sign in again to reveal`: the login is older than the window, or its `auth_time` is more than a minute ahead of the backend's clock | `stale_login` |
| 409 | `entered before encryption — enter it again`: the Secret has no sealed row | `no_ciphertext` |
| 409 | `stored under another key`: the row was sealed under a key this backend no longer holds | `key_changed` |
| 409 | `cannot be read`: the ciphertext does not open | `unreadable` |

A token without the admin role is 403 `forbidden` and is not recorded. A reveal that succeeds is recorded as
`revealed`. No value is in a log line or an event.

## The chat endpoint

```
POST /v1/spaces/acme/airlocks/hr/chat
Authorization: Bearer <token>
Accept: text/event-stream
Content-Type: application/json

{"message": "What is my gross salary?"}
```

- The message is at most 4,000 characters; a blank or longer one is 400.
- The release's Ingress routes the path to that airlock's pod. At a pod, any other airlock is 404.
- At most three tool calls per exchange. One exchange runs at most `AISOLATE_EXCHANGE_MAX_DURATION`.
- The answer is grounded: when every evidence is empty the answer is exactly "I don't know". Every figure in
  it is written in by AIsolate from a cell the model cites, or computed from such cells, and checked; a number or
  a fact value the model typed itself is flagged unsourced ([Verification](verification.md)).
- `Accept: text/event-stream`, or no preference (`*/*`, none), is the stream below. A caller that cannot read
  server-sent events, such as a workflow tool or a script, sends `Accept: application/json` and gets the whole
  exchange as one document, with the same token, admission and rules. Send exactly `Accept: application/json`;
  a list that also contains `*/*` or `text/event-stream` gets the stream.

```
POST /v1/spaces/acme/airlocks/hr/chat
Authorization: Bearer <token>
Accept: application/json
Content-Type: application/json

{"message": "What is my gross salary?"}
```

```json
{
  "answer": "Your gross salary is 78,000 EUR.",
  "dont_know": false,
  "segments": [{"text": "Your gross salary is "}, {"figure": "f1"}, {"text": "."}],
  "figures": [{"id": "f1", "display": "78,000 EUR", "value": "78000", "status": "sourced",
    "source": {"evidence": "ev-1", "entity": "hr.compensation", "column": "gross_salary", "row_key": "3",
               "subject": "Eric Lindholm", "unit": "EUR"}}],
  "tables": [],
  "quotes": [],
  "provenance": {"status": "all_sourced", "sourced": 1, "computed": 0, "unsourced": 0, "mismatch": 0},
  "evidence": [{"id": "ev-1", "source": "read_my_records", "name": "hr.compensation", "rows": 1, "data_source": "hr"}],
  "tool_errors": [],
  "verification": {"verified": false, "verdicts": []},
  "revision": "a2cf258ece3d",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

- `answer` is the text the user gets, every figure written in. `dont_know` is true when it is empty or begins
  with "I don't know", leading emphasis or quote marks aside.
- `segments` is the same answer in pieces: `{"text": …}`, `{"figure": "f1"}` (one of `figures`) or `{"table":
  "t1"}` (one of `tables`), in order, so a client can show each figure with its badge. A quote is text, its words
  in quotation marks; `quotes` says where it comes from.
- `figures`, in the order the answer places them: `id` (`f…` as the model named it, `u…` for a number or a fact value it typed),
  `display` as written in, `value` exact and plain (`78000`, `15.1020408163`, a text cell's text; absent for a
  ranking and for an unsourced or mismatched figure), `status` `sourced`, `computed`, `unsourced` or `mismatch`,
  and `reason` for a mismatch, or for an unsourced number or fact value that it was typed and what cell holds the same value.
  - A sourced figure has `source`: `evidence`, `entity`, `column`, `row_key`, and the row's labels `subject`,
    `period` and `unit`, each absent when the entity declares none (`unit` also for a cell that is no number).
  - A computed one has `computed`: `op` and `of` (each operand a source with its `value` and `display`); for
    `top_n`/`bottom_n` `n`, `by` (`evidence`, `entity`, `column`) and `rows` (each a source with its `value` and
    `display`), its `display` the rows' names in rank order; for a `max` or `min` the `row` holding it, and `by`
    when it was taken over a column of one tool result; for a ranking, and a max or min over a column, the `call`
    (`tool`, `args`), which says the query's scope.
  - A mismatch keeps the `source` or `computed` its reference resolved to, and its `display`, when only a claim
    conflicts; one whose reference resolves nowhere is `display` `…` alone. The model's own claims of what a
    figure is about (`about`: `subject`, `period`, `concept`, `unit`, [Verification](verification.md#grounded-answers))
    are not returned: each is checked against the row, and a conflict is the `reason`.
- `tables`: `id`, `status` (`sourced` or `mismatch`), `display` (the markdown table `answer` holds), `evidence`,
  `entity` and `call` (`tool`, `args`; both absent when the evidence is no tool result), `columns`, `rows` (each
  cell `display`, `value`, `status` and `source`; a cell that resolves nowhere is `…`, `mismatch`, nothing more),
  `rank` (`op`, `by`) when the rows claim one, `reason` for a mismatch.
- `quotes`: `id`, `status` (`quoted` or `mismatch`), `evidence`, `index`, the passage's `title` and `section`,
  `text`, `reason` for a mismatch.
- `provenance`: `status` `all_sourced`, `has_unsourced`, `has_mismatch` or `no_figures`, and the four counts;
  a table counts once. What each check proves: [Verification](verification.md).
- `evidence` lists provenance only, never rows or text: `source` is the tool (or the context preparator that
  added passages), `name` what it read (the entity; the preparator again for passages), `rows` the rows or
  passages it holds, `data_source` the data source a tool read (`null` for passages).
- `tool_errors` lists the tool calls that returned no evidence, as the `tool_error` frames below: `call_id`,
  `tool`, `error`.
- `verification`: `verified` is true when at least one verifier ran and every verdict is `PASS`; without
  verifiers it is false and `verdicts` is empty. Each verdict is `name` (the verifier) and `verdict`, `PASS`,
  `WARN` or `FAIL`.
- `revision` is the release of the airlock that answered. `trace_id` is the exchange's trace, the one its
  audit line names ([Observability](observability.md#traces)); the airlock starts it, whatever `traceparent`
  the caller sent, and it is `null` only where nothing traces.
- Those twelve fields, in that order, are the whole document.
- An exchange that cannot finish is a problem document, not a frame: 503 "The model is not available right
  now. Please try again in a moment." when the model server refuses, times out or cannot be reached, 503 "The
  airlock's model endpoint is not available" when the airlock names a model this AIsolate has no endpoint for,
  and 500 "The assistant could not complete the request" otherwise.

## SSE events

Each frame carries an `event:` line naming its type and a `data:` line with one JSON object. Tool calls and
evidence stream as they happen; the answer arrives as one `answer_chunk` once the exchange is finished.

```
event: tool_call
data: {"id":"call-1","tool":"read_my_records","args":{"entity":"hr.compensation"},"type":"tool_call"}

event: evidence
data: {"id":"ev-1","call_id":"call-1","tool":"read_my_records","entity":"hr.compensation","row_count":1,"columns":[...],"rule_version":"1.a2cf258ece3d","rules":["own-record"],"sql_hash":"...","data_source":"hr","type":"evidence"}

event: answer_chunk
data: {"content":"Your gross salary is 78,000 EUR.","type":"answer_chunk"}

event: verification
data: {"verdicts":[{"handler":"PayslipVerifier","status":"PASS","note":"matches the payslip"}],"type":"verification"}

event: done
data: {"evidence_ids":["ev-1"],"grounded":true,"answer":"Your gross salary is 78,000 EUR.","segments":[...],"figures":[...],"tables":[],"quotes":[],"provenance":{"status":"all_sourced","sourced":1,"computed":0,"unsourced":0,"mismatch":0},"type":"done"}
```

- `answer_chunk` carries the answer as the user gets it, every figure written in; `done` carries it again with
  `segments`, `figures`, `tables`, `quotes` and `provenance`, exactly as the JSON answer above, after
  `evidence_ids` (every evidence of the exchange, cited or not) and `grounded` (whether it gathered any).
- `verification` comes only when the airlock has verifiers.

- Passages from a context preparator arrive as an `evidence` frame too, with the context preparator's id as
  `tool` and `entity`, `call_id` and `data_source` null, and the titles and sections as columns.
- `call_id` is numbered per exchange, so a tool called twice still pairs each result with its call.
- A tool call that returns no evidence is followed by a `tool_error` frame instead,
  `{"call_id":"call-1","tool":"read_my_records","error":"login_refused","type":"tool_error"}`: `error` is
  `login_refused` (its data source refused the login), `unreachable` (it could not be reached) or `failed`
  (anything else), never the tool server's words. When every tool call failed on its data source, the answer is
  "I couldn't reach the data to answer this." rather than "I don't know".
- `evidence` frames never contain rows or text.
- An `error` frame with a fixed message replaces `answer_chunk` and `done` when the exchange cannot finish:
  "The model is not available right now. Please try again in a moment." when the model server refuses, times
  out or cannot be reached; "The airlock's model endpoint is not available" when the airlock names a model provider
  this AIsolate has no client for; "The assistant could not complete the request" for the tool-call limit, the
  exchange deadline and anything unexpected. The cause stays in the pod's log.
- AIsolate queues nothing in front of the model: a busy model server reaches the user as the first of those
  two messages, and the user retries.

## The audit endpoints

The audit trail lives in the backend's database ([Audit](audit.md)). Its readers: `aisolate-admin` and
`aisolate-auditor`, which reads the audit and nothing else, every space's; a developer the events of the spaces they
develop, the list filtered to them and another space's event or chain 403. Any other token is 403.

### `GET /v1/audit/events`

The audit lines, newest first (in the order the backend received them), a page at a time.

| Parameter | |
|---|---|
| `space`, `airlock` | only that space's or airlock's lines |
| `user` | only that user's (the token's e-mail, case ignored) |
| `outcome` | `answered`, `dont_know`, `refused` or `error` |
| `trace_id` | only the line of that exchange's trace (the chat answer's `trace_id`) |
| `q` | only lines whose space, airlock, user, client, trace id or question holds it, case ignored (the Audit page's search) |
| `from`, `to` | when the exchange began (`ts`), both included: a date in UTC (`2026-10-01`) or an instant (`2026-10-01T08:00:00Z`) |
| `cursor` | the `next_cursor` of the page before |
| `limit` | 1 to 200, default 50 |

```json
{
  "events": [
    {"id": 1042, "received_at": "2026-10-18T09:12:03.120Z", "ts": "2026-10-18T09:11:57.006Z",
     "event": "exchange", "space": "acme", "airlock": "hr", "revision": "7b2280eab4b5",
     "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "user": "elena@example.com", "client": "acme-chat",
     "outcome": "answered", "reason": null, "question": "What is my gross salary?",
     "figures": {"sourced": 1, "computed": 0, "unsourced": 0, "mismatch": 0},
     "input_tokens": 1840, "output_tokens": 212, "duration_ms": 6114}
  ],
  "next_cursor": "1041"
}
```

Each entry is the line's summary: the fields of the [audit line](security.md#audit-trail) of the same names,
`input_tokens` and `output_tokens` its `model`'s (`null` when the provider reported none, and for a refusal),
`figures` `null` when nothing was answered. `next_cursor` is `null` on the last page; a cursor is opaque. A
parameter that is not one of the above values is 400.

### `GET /v1/audit/events/{id}`

One line with all it recorded, and its link in the chain:

```json
{
  "id": 1042,
  "received_at": "2026-10-18T09:12:03.120Z",
  "prev_hash": "9c1d…64 hex digits",
  "hash": "e3b0…64 hex digits",
  "event": {"ts": "2026-10-18T09:11:57.006Z", "event": "exchange", "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
            "space": "acme", "airlock": "hr", "revision": "7b2280eab4b5",
            "user": "elena@example.com", "client": "acme-chat", "question": "What is my gross salary?",
            "answer": "Your gross salary is 78,000 EUR.", "outcome": "answered", "reason": null,
            "tools": [{"name": "read_my_records", "args": {"entity": "hr.compensation"}, "data_source": "hr",
                       "rows": 1, "rule_version": "1.7b2280eab4b5", "sql_hash": "3f9a1c0d2b7e", "rows_hash": "5c1e…",
                       "error": null}],
            "evidence": ["ev-1"], "verifiers": [],
            "figures": {"sourced": 1, "computed": 0, "unsourced": 0, "mismatch": 0},
            "grounding": {"segments": [{"text": "Your gross salary is "}, {"figure": "f1"}, {"text": "."}],
                          "figures": [{"id": "f1", "display": "78,000 EUR", "status": "sourced",
                                       "source": {"evidence": "ev-1", "entity": "hr.compensation",
                                                  "column": "gross_salary", "row_key": "3"}}],
                          "tables": [], "quotes": []},
            "model": {"provider": "ollama", "name": "qwen3.6:27b-q4_K_M", "calls": 2,
                      "input_tokens": 1840, "output_tokens": 212},
            "duration_ms": 6114}
}
```

`event` is the audit line as the airlock pod sent it, every field described in [Security, Audit
trail](security.md#audit-trail). Its `grounding` holds the answer's `segments`, `figures`, `tables` and
`quotes` in the shapes of [the chat endpoint](#the-chat-endpoint), less what the cells held: no `value`,
`subject`, `period` or `unit` anywhere in it (the rows stay with the data; `display` is what the user was
told). So the answer can be shown with where each figure came from; `null` for a refusal, an error, or a
line that predates it. `hash` is
`sha256(prev_hash || canonical event)` in hex, `prev_hash` the hash of the airlock's line before it (64 zeros
for the airlock's first). 404 `unknown audit event`.

### `GET /v1/audit/export?format=&q=&from=&to=&space=`

Every line the list finds for `q`, `from`, `to` and `space` (the same parameters, the same access: a developer
gets their spaces' lines, another space's 403), every page of it, as one file to download
(`Content-Disposition: attachment`, named for the day in UTC). `format` is required:

| `format` | |
|---|---|
| `pdf` | `aisolate-audit-2026-10-19.pdf`: a plain report to read. Its heading: the search, the period, when and by whom it was generated; then one block per line, newest first: the time (UTC), space / airlock, user, outcome (and its reason), question, answer, the tools called with their rows, tokens and duration. A character its font cannot draw prints as `?` |
| `json` | `aisolate-audit-2026-10-19.json`: `{"events": [...]}`, newest first, each entry what `GET /v1/audit/events/{id}` gives: `id`, `received_at`, `prev_hash`, `hash` and `event`, the line as stored |
| `xbrl` | `aisolate-audit-2026-10-19.xbrl`: the AIsolate Audit report ([Audit](audit.md#export)), one XBRL 2.1 instance in XML (xBRL-XML), each exchange a context of the typed dimension `aisolate:ExchangeAxis`. Its period is whole UTC days, from `from`'s (the oldest line's without one) to `to`'s (today without one); its entity `AISOLATE_AUDIT_ENTITY` ([Configuration](configuration.md)). A line the report cannot use is left out |

Another `format` is 400 `format: pdf, json or xbrl`. The file is built in the backend's memory.

### `GET /v1/audit/events/{id}/export?format=`

One event alone, in the same three formats, named for it: `aisolate-audit-event-15.pdf`, `.json` or `.xbrl`; the AIsolate UI's
**Export as** on the event's page. The access of `GET /v1/audit/events/{id}`; 404 `unknown audit event`, 400 for another `format`.

### `GET /v1/audit/verify?space=&airlock=`

Re-walks one airlock's chain, oldest to newest, and says whether it is intact:

```json
{"space": "acme", "airlock": "hr", "status": "ok", "events": 1042, "first_id": 1, "last_id": 1042,
 "head": "e3b0…64 hex digits", "broken": null}
```

```json
{"space": "acme", "airlock": "hr", "status": "broken", "events": 1042, "first_id": 1, "last_id": 1042,
 "head": "e3b0…", "broken": {"id": 517, "reason": "altered"}}
```

`status` is `ok` or `broken`; an airlock without lines is `ok` with `events` 0 and the ids and `head` `null`.
`broken` names the first line that fails: `altered`, its content (the line or a column read from it) no longer
gives its `hash`; `removed`, its `prev_hash` is not the hash of the line before it: a line between them is
gone, or the one before was rewritten with a new hash. The walk starts at the oldest line kept: lines past the retention are dropped a month at a time and
their successor anchors the chain ([Audit, Retention](audit.md#retention)). Both parameters are required (400).

### `GET /v1/audit/data-error?space=&airlock=`

Whether the airlock's newest exchange that called a tool could read its data sources, from its audit line:

```json
{"data_error": {"data_source": "hr", "error": "login_refused", "ts": "2026-10-18T10:43:02.117Z"}}
```

`error` is the category the airlock recorded on the failed call: `login_refused` (the database refused the
login) or `unreachable` (it could not be reached); any other failure, a call that read its data, or no tool
call yet is `{"data_error": null}`. The next exchange that reads the data clears it. The AIsolate UI shows it
on the airlock's page. Both parameters are required (400).

### `POST /v1/audit/events`

How an airlock pod hands over its audit lines; nothing else calls it. Served on the backend's ingest port
(8085), which only an airlock's pods reach (the backend's NetworkPolicy admits them alone, and theirs reaches no
other port of the backend); the API port answers it 404, and the ingest port answers nothing else but the
[code fetch](#the-code-fetch).

```
POST /v1/audit/events
Authorization: Bearer <the airlock's ingest token>
Content-Type: application/json

{"ts": "2026-10-18T09:11:57.006Z", "event": "exchange", "space": "acme", "airlock": "hr", …}
```

201 `{"id": 1042}`; an exact repeat of a line already stored (a retry whose answer was lost) is 200 with the
stored line's `id`. No token is 401. A token that is not the ingest token of the airlock the line names (its
`space` and `airlock`) is 403: one airlock's pod cannot write another's lines. A body that is no
audit line (no `ts`, `event`, `space`, `airlock` or `outcome`, or an unknown `event` or `outcome`)
is 400. The token is no user's: this and the code fetch are the endpoints that take no user token
([Security, Audit trail](security.md#audit-trail)).

## The trace endpoints

What the AIsolate UI's **Monitoring > Tracing** reads ([Observability](observability.md#in-the-aisolate-ui)). Both
are 404 `no trace store is connected` unless the backend has `AISOLATE_TRACING_QUERY_URL`. Their readers:
`aisolate-admin`, every space's; a developer the traces of the spaces they develop. The auditor and anyone else
get 403: the audit pages are theirs.

### `GET /v1/traces`

The exchanges and refusals, newest first, read from the audit trail as `GET /v1/audit/events` reads it, a page
at a time. Parameters `space`, `airlock`, `from`, `to`, `cursor` and `limit` as there, and `q`: only the exchanges
whose question holds that text, case ignored (`%` and `_` are characters, not wildcards). A developer naming
another space's `space` gets the 403 that names it.

```json
{
  "traces": [
    {"id": 1042, "ts": "2026-10-18T09:11:57.006Z", "event": "exchange", "space": "acme", "airlock": "hr",
     "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736", "user": "elena@example.com", "outcome": "answered",
     "reason": null, "question": "What is my gross salary?", "model": "granite4.1:8b", "model_calls": 2, "tool_calls": 1,
     "input_tokens": 1840, "output_tokens": 212, "duration_ms": 6114}
  ],
  "next_cursor": "1041"
}
```

`id` is the audit line's; `model`, `model_calls` and `tool_calls` are its `model.name`, `model.calls` and the
number of its `tools` (`null` on a refusal).

### `GET /v1/traces/{trace_id}`

One exchange: its audit line, and its spans as the trace store holds them, as a tree of steps.

```json
{
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "event_id": 1042,
  "event": {"ts": "2026-10-18T09:11:57.006Z", "event": "exchange", "question": "What is my gross salary?", …},
  "spans_status": "held",
  "steps": [
    {"id": "ca37c9288923845c", "name": "http post /v1/spaces/{spaceId}/airlocks/{airlockId}/chat",
     "kind": "http", "service": "aisolate-airlock", "start_us": 0, "duration_us": 8565120,
     "error": false, "error_type": null, "attributes": {"http.response.status_code": 200, …},
     "children": [{"name": "exchange acme/hr", "kind": "exchange", …, "children": […]}]}
  ]
}
```

`event` is the audit line as `GET /v1/audit/events/{id}` gives it: the question, the answer and the tools'
arguments and row counts come from it, never from a span ([What never goes into a
span](observability.md#what-never-goes-into-a-span)). `spans_status` is `held`; `gone` when the store no longer
holds the trace (Jaeger keeps traces for a while only) or its spans name another space than the line; or
`unavailable` when the store did not answer. `steps` is `null` unless `held`.

A step is a span: `kind` one of `exchange`, `admission`, `handler`, `verifier`, `embedding`, `model`, `tool`,
`mcp`, `query`, `sql`, `http` or `other`, read from its name and attributes ([Runtime contract](runtime-contract.md) §8); `start_us` microseconds after the trace's first span, `duration_us` its own; `error` when its status is
ERROR, `error_type` the exception's class. `attributes` are the span's, without the space and airlock every span repeats,
the exporter's bookkeeping, a status description or an exception attribute. Children are ordered by start; a span
whose parent the trace lacks is a root. A trace id that no line of the caller's spaces names, or that is not 32
hex digits, is 404 `unknown trace`.

## The metrics endpoint

`GET /v1/metrics?space=&airlock=&period=` is what **Monitoring > Metrics** reads: the five panels of the chart's
example dashboard, from Prometheus in the cluster (`AISOLATE_METRICS_QUERY_URL`; 404 `no metrics store is
connected` without one). The backend runs its own fixed queries; nothing a caller sends becomes a query but a
space or an airlock id, each checked against the id rule first (400 `space: not an id`). Every query carries a
`space` matcher for a developer, their spaces alone (a developer naming another space gets the 403 that names it),
and none for the admin; the series need the `space` label the scraper copies from the pod
([Observability, Metrics](observability.md#metrics)). `period` is `10m`, `1h`, `24h` (the default), `7d` or `30d`; 502 `the
metrics store did not answer` when Prometheus fails.

```json
{
  "from": "2026-10-18T10:42:00Z", "to": "2026-10-19T10:42:00Z", "step_seconds": 720,
  "exchanges_per_minute": [{"space": "acme", "airlock": "hr", "points": [[1792399400, 0.5], [1792400120, 1.5]]}],
  "tokens_per_minute": [{"space": "acme", "airlock": "hr", "points": [[1792399400, 9210.4]]}],
  "p95_duration_seconds": [{"space": "acme", "airlock": "hr", "points": [[1792399400, 8.2]]}],
  "refusals": [{"space": "acme", "airlock": "hr", "name": "user", "count": 7}],
  "tool_calls": [{"space": "acme", "airlock": "hr", "name": "read_records", "count": 214}]
}
```

A line's points are [epoch seconds, value], one per `step_seconds`, a value Prometheus could not compute left out:
exchanges and tokens (in and out, never the provider's total) per minute, the 95th percentile of the exchange
duration in seconds. A bar counts over the whole period, what it counts in `name`: a refusal's reason, a tool;
the largest first, none that is zero. The queries are the dashboard's (`examples/grafana-dashboard.json`), per
minute where it is per second, each rate's window at least the step.

## The code fetch

`GET /v1/code/{space}/{airlock}/{digest}`, on the backend's ingest port 8085 alone (the API port answers
404, and no Ingress routes to it): how an airlock's two pods get its artifact at start
([runtime contract](runtime-contract.md)). `Authorization: Bearer <code token>`, the token the controller renders
into the airlock's Secret: HMAC-SHA256 of `code/<space>/<airlock>` under the install's ingest key, so it
admits that airlock's code alone and no audit line. `{digest}` is the `sha256:<hex>` of `spec.jar.digest`.

- 200 `application/octet-stream` with `Content-Length`: the bytes, streamed from the database a chunk at a time;
  the pod checks their sha256 itself and refuses to start on a mismatch;
- 401 without a token, 403 for a token not this airlock's code token (another airlock's, the ingest token, a
  user's), 404 when the airlock holds other code than `{digest}` or none.

## Errors

Errors are RFC 9457 problem documents.

| Status | When |
|---|---|
| 400 | An invalid form, with field errors; a blank or too long message; a deploy the backend refuses, with the reasons |
| 401 | No token, a forged or expired one, or one for another audience, with `WWW-Authenticate: Bearer resource_metadata=…` |
| 403 | `forbidden`. At an airlock pod: a user neither its space nor its airlock lists, or a calling client it does not admit; the reason goes to the pod's log (`<space>/<airlock> does not admit roles [<roles>] from client <azp>`) ([The gates](security.md#the-gates)). At the backend: a token without the UI roles; a reveal's refusals add a `reason` property (`wrong_client`, `no_auth_time`, `stale_login`) ([reveal](#post-v1credentialssecretrefreveal)) |
| 404 | `unknown airlock`, `unknown space`, `unknown credential`; at a pod, any path but its own |
| 409 | A reveal's three refusals (`no_ciphertext`, `key_changed`, `unreadable`) ([reveal](#post-v1credentialssecretrefreveal)). A create whose id is taken, then one whose cluster name, `<space>` or `<space>-<airlock>`, another one has (a deploy's too): `An airlock with the id hr already exists in this space.`, `The id cd-ef can't be used here: it would collide with the airlock ef in space ab-cd. Choose another id.` The same message is in `errors`, on its field (`id`). An id derived from the name skips both |
| 500, 503 | The JSON form of a chat exchange that could not finish, as the [chat endpoint](#the-chat-endpoint) says: 503 when the model server cannot be reached or the airlock's model has no endpoint, 500 otherwise; the stream puts the same sentence in an `error` frame |

## The deploy call

`POST /spaces/{space}/deploy`, multipart:

| Part | Content |
|---|---|
| `jar` | The project as `aisolate build` built it: a Java project's `build/libs/<project>.jar` (its classes and `aisolate/rules/`), or a Python project's `build/aisolate/<package>.zip` ([The Python artifact](python-artifact.md)) |
| `language` | `python` for a Python project's zip; `java`, or no part, for a jar |
| `documents` | The project's `aisolate.yaml` as written: one `Airlock` |
| `variables` | JSON `{NAME: value}` of the variables the file reads |

The backend reads the code first and refuses (400) code it cannot use:

- `jar: required`, `jar: not a jar file`, and, only when the operator set a limit (`AISOLATE_MAX_ARTIFACT_SIZE`, the
  chart's `platform.backend.maxArtifactSize`; none by default), `jar: larger than 500 MiB, the most this install takes`;
- `jar: nothing to load: no ToolPlugin class, no handler class, no aisolate/rules file`;
- `jar: handler SessionMemory is declared twice`;
- code the MCP server would refuse to start with, in [its words](configuration.md#mcp-server): `jar: tool
  read_records is published twice`, `jar: plugin hr-tools declares entities but names no data source`,
  `jar: rule set hr.compensation: rule own-record names column ibna no plugin declares`;
- `jar: aisolate/rules/<path>: a rule set is rules/<entity>.json`;
- `language: java or python, not <language>`.

The upload is written to disk as it arrives and stored in 1 MiB chunks, never held whole in memory. A Python zip is
read, never extracted or imported: its `aisolate-manifest.json` against
[its schema](python-artifact.schema.json), its rule files, then the same checks as a jar's, each error starting
`artifact: `: `artifact: not a zip file`, ``artifact: no aisolate-manifest.json: build the project with
`python -m aisolate build` ``, `artifact: aisolate-manifest.json /language: …` (a schema error, at its JSON
pointer), `artifact: entry <name> is a symlink`, `artifact: entry <name> is not a plain path inside the zip`,
`artifact: aisolate-manifest.json lists the rule files [...], the zip holds [...]`, `artifact: tool <name> names
plugin <plugin>, which the manifest does not list`, and the catalog's own words (`artifact: rule set
hr.compensation: rule own-record names column ibna no plugin declares`).

Then the file. It refuses (400):

- anything but one `Airlock` (a `Space`, a second `Airlock`, another kind):
  `aisolate.yaml: a project is one Airlock; the Space is defined in the AIsolate UI`;
- an `Airlock` with a space, `aisolate.yaml: Airlock hr: spec.space is AISOLATE_SPACE's to set; leave
  it out`; then, in the loader's words, an unknown field or a value it refuses (`aisolate.yaml: Airlock hr:
  …`), and, each prefixed `Airlock <name>: `, what the airlock form refuses, such as `roles: * is no role: list each
  role; only callers take * for any`;
- a data source with a `secretRef` (`… spec.dataSources[i].secretRef is AIsolate's to set; give username and
  password`), and a `spec.jar` (`… spec.jar is the deploy's to set`);
- a model with a `secretRef` (`… spec.llms[i].secretRef is AIsolate's to set; give the model's API key in the
  AIsolate UI`): a project holds no key;
- a variable without a value, a URL that carries a credential, an LLM provider other than `ollama` or `openai`;
- a tool the file lists and the code lacks: `aisolate.yaml: Airlock <name>: spec.tools
  export_payslips is not a tool of this airlock's code`;
- then, each problem prefixed
  `Airlock <name>: `, an `llm` that the file, the space and the global LLMs do not define
  (`llm: no model named granite in this airlock's space or globally`; ids the code names are not checked), an id past
  Kubernetes' 63 characters (`id: it would run as …`), a handler the code lacks (`context_preparators: X is not a context preparator of this airlock's
  code`, likewise `memory_handlers` and `verifiers`), and a data source a tool plugin names and nothing defines (`tools: plugin hr-tools reads
  data source payroll, which this airlock does not define`);
- then, 409, an id whose cluster name another one has:
  `id: The id cd-ef can't be used here: it would collide with the airlock ef in space ab-cd. Choose another id.`

An airlock id another space holds
is 409, `An airlock with the id hr already exists in space other.`; an unknown space is 404. Otherwise, in one
transaction, the file's `Airlock` creates the airlock, or updates in place the one the AIsolate UI or an earlier
deploy created: each field it writes is saved over the stored one (a field it leaves out keeps what the AIsolate
UI or the last deploy set; nothing different, nothing saved), with the new code, stages, prompt, main LLM, LLMs,
data sources and description of the project and a new revision; each data source's credentials become a Secret;
the space is applied. Nothing is deleted.

The response carries `airlock: {id, name, revision, endpoint, created, updated}` (both false: as it was),
`jar: {digest, tools, handlers}`, `warnings` and `cluster: {applied, problem}`. A warning names what the code
and the file leave unused: `tool export_payslips of this project is not in tools, so the model cannot call
it`, `verifier X of this project is listed nowhere and runs nowhere` (likewise a context preparator or a
conversation memory).
