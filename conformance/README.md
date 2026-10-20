# Conformance

The guard for running AIsolate in two languages: the Java runtimes (`apps/airlock`, `apps/tool-server`) and the
Python ones (`apps/airlock-python`, `apps/tool-server-python`) must behave identically wherever security or the
audit trail is concerned. The behaviour is written down once, as data, in `cases/`; each runtime has a thin runner
that feeds the same files to its own code. Neither image ships unless both runners pass in `./gradlew build`.

The expected values were produced by running the Java classes named below on the case inputs, so the Java runtime
is the reference. A case that fails for one runtime is a divergence to fix, never a case to edit for one side.

## Layout

| Directory | What it pins | Java code | Java runner | Python runner |
|---|---|---|---|---|
| `cases/admission/airlock.json` | Gate 2 and gate 5's admission: users × callers × claims | `Principal.admissionFailure` | `libs/identity` `AdmissionConformanceTest` | `apps/airlock-python/tests/conformance/test_admission.py` and `apps/tool-server-python/tests/conformance/test_admission.py` |
| `cases/admission/token.json` | Gates 1 and 4: signature, issuer, audience, time | `libs/identity` `SecurityConfig` (Spring Boot resource-server defaults) | `apps/airlock` `TokenConformanceTest`, `apps/tool-server` `TokenConformanceTest` | `tests/conformance/test_token.py` in both Python apps |
| `cases/admission/tool-server.json` | Gate 5 before any query: the `_meta` pin, admission, the tool list; the silent empty result and its metric | `GovernedQueryService.call`, `PolicySource`, `GovernedToolsConfig` | `apps/tool-server` `ToolServerAdmissionConformanceTest` | `apps/tool-server-python/tests/conformance/test_tool_admission.py` |
| `cases/rules/compensation.json`, `cases/rules/vitals.json` | Rule evaluation, the compiled statement, the result JSON and its rows | `RulesEvaluator`, `ToolArgs`, `SqlCompiler`, `GovernedQueryService.Bound`, `Result` | `apps/tool-server` `RulesConformanceTest` | `apps/tool-server-python/tests/conformance/test_rules.py` |
| `cases/rules/rule-set-load.json` | Which rule-set files load, and the exact refusal | `RuleSet.parse`, `Rule`, `RowFilter` | `libs/config` `RuleSetConformanceTest` | `apps/tool-server-python/tests/conformance/test_rule_set_load.py` |
| `cases/sql-safety/compensation.json` | The model's arguments: shaping, validation, the exact statement and parameters, the exact tool errors | `sdk.Arguments`, `ToolArgs`, `SqlCompiler` | `apps/tool-server` `SqlSafetyConformanceTest` | `apps/tool-server-python/tests/conformance/test_sql_safety.py` |
| `cases/errors/data-source.json` | A tool call whose read fails: its error (`login_refused`, `unreachable`, `failed`) from the exception's causes, the model's sentence, `_meta` for the airlock | `ToolError`, `GovernedToolsConfig` | `apps/tool-server` `DataSourceErrorConformanceTest` | `apps/tool-server-python/tests/conformance/test_data_source_errors.py` |
| `cases/audit/` | The audit event: shape and order, outcome, `rows_hash`, what the grounding keeps | `AuditEvent`, `AuditLog`, `ExchangeAudit`, `Answers`, `ChatExchange.rowsHash` | `apps/airlock` `AuditConformanceTest` | `apps/airlock-python/tests/conformance/test_audit.py` |
| `cases/grounding/` | The golden set | see Grounding below | `apps/airlock` `GoldenSetTest` | `apps/airlock-python/tests/conformance/test_grounding.py` |
| `cases/prompts/airlock.json` | The sentences the model and the browser read byte for byte: the platform preamble, the answer format (instruction, schema, in-format turn, retry), the nudges, `I don't know`, the three error sentences | `ChatExchange`, `AnswerFormat`, `ChatEvent.Error` | `apps/airlock` `PromptsConformanceTest` | the Python airlock reads its prompts from this file; `apps/airlock-python/tests/conformance/test_prompts.py` |
| `cases/delivery/fetch.json` | A pod's start: the code fetched from the backend, its sha256 checked; a mismatch or a refused fetch fails the start | `libs/plugins` `CodeFetch` | `libs/plugins` `CodeFetchConformanceTest` | `libs/runtime-python/tests/conformance/test_fetch.py` |
| `cases/llm/catalog.json` | The LLMs and data sources an airlock sees over the three levels, its main LLM, and the loader's refusals | `Configuration.llmsOf`, `mainLlmOf`, `dataSourcesOf`, `ConfigurationLoader` | `libs/config` `LlmCatalogConformanceTest` | `libs/runtime-python/tests/conformance/test_llm_catalog.py` |
| `cases/llm/errors.json` | The airlock pod's LLM catalog from env and its start check; `llm(id)`, `embed`, `chat`: the requests, and the plain errors for an unknown id or a model that cannot embed or chat | `apps/airlock` env catalog reader, `Llm`, `LlmException` | `apps/airlock` `LlmErrorsConformanceTest` | `apps/airlock-python/tests/conformance/test_llm_errors.py` |

The test names are the ones to create; the class and module names are the builders' choice as long as the table is
kept true.

## How a runner finds the cases

- **Java**: a JUnit 5 `@ParameterizedTest` with `@MethodSource` reading
  `Path.of(System.getProperty("aisolate.conformance.dir"), "<dir>", "<file>.json")`: the root `build.gradle.kts`
  passes that property to every module's tests and declares `conformance/cases` as a test input, so a changed case
  reruns them (see `GoldenSetTest`, `PromptsConformanceTest`). Jackson with `USE_BIG_DECIMAL_FOR_FLOATS`, so
  `78000.00` stays exact; one `Arguments` per element of `cases`, named by its `id`.
- **Python**: `pytest.mark.parametrize` over the same files, found by walking up from `Path(__file__).resolve()` to
  the directory holding `conformance/cases`, read with `json.loads(text, parse_float=decimal.Decimal)`, `ids=` the
  case ids. Test functions follow `test_given_<state>_when_<action>_then_<outcome>` with `# Arrange`, `# Act`,
  `# Assert`.
- Every file is one JSON object: `description`, the shared inputs, and `cases`, each with an `id` (unique in its
  file), sometimes a `description`, its inputs and `expect`.

## Comparing

- **Numbers** compare as decimals: `78000`, `78000.00` and Postgres' `78000.000000000000` are equal. Strings,
  booleans and `null` compare exactly.
- **SQL** compares byte for byte: `sql_hash` (the first 12 hex digits of its sha256) is on every audit line, so the
  two runtimes must build the very same text for the same query.
- **Parameters** compare element by element, in order, numbers as decimals.
- **Rows** of a read compare in order (`ORDER BY` the row key, the asker's own row first). Rows of an aggregate
  compare as a multiset: an aggregate has no `ORDER BY`, and adding one would change its `sql_hash`.
- **`hidden` and `masked`** are sets; the files list them sorted.
- **Tool errors** compare exactly: `expect.error` is the text of the MCP tool result (`isError: true`), always
  `Invalid arguments: <message>`.

## admission/airlock.json

Shared inputs: `space.users`; `airlocks.<id>.{users, callers}`. A case: `airlock`, `claims` (the validated token's
claims), `expect.admitted`, and when refused `expect.reason` (`user` | `client`).

The runner computes the allowed users as the space's users followed by the airlock's own, without duplicates
(`Configuration.accessOf`), the callers as the airlock's, and calls its admission over a principal built from
`claims` with roles claim `realm_access.roles`. The rules it pins: the e-mail is matched case-insensitively and
untrimmed against the list, `*` among the users is no wildcard; the `azp` is matched exactly, `*` among the callers
admits any present `azp`; a missing or non-string `email` refuses as `user`, a missing or non-string `azp` as
`client`; the user is checked first; roles never admit. The airlock answers a refusal 403
(`application/problem+json`, detail `forbidden`) after writing one `refusal` audit event with that reason; the tool
server answers it with the empty result of `admission/tool-server.json`.

## admission/token.json

Shared inputs: `issuer`, `audience`, `base_claims`. A case: `signed_by` (`issuer_key`, `other_key`, `none`,
`hs256_public`, or `null` for no `Authorization` header), `claims` merged over `base_claims` (a `null` value removes
the claim; `iat`, `exp`, `nbf` are seconds relative to now), or `raw` (the bearer value as is), and `expect`
(`"valid"` or `401`). The runner serves a JWKS holding the issuer key (kid `conformance`, RS256) as the issuer's
discovery and points the app's decoder at it. What is pinned: RS256 from the issuer's JWKS only, `iss` exactly the
configured issuer, `aud` (string or list) containing `aisolate`, `exp` and `nbf` with 60 seconds of clock skew. A
401 carries `WWW-Authenticate: Bearer resource_metadata="<public url>/.well-known/oauth-protected-resource"` and the
body `{"status":401,"title":"Unauthorized"}`.

## admission/tool-server.json

Shared inputs: `served_revision`, `space.users`, `policies` (airlock, own users, callers, tools),
`published_tools` (tool → the data source of the plugin publishing it). A case: `claims`, `meta` (the `_meta` of
the `tools/call`; `null` for none), `tool`, and `expect`: `admitted: true` (the call goes on to the rules), or
`admitted: false` with the exact tool result JSON (`result`) and the labels of the one increment of
`aisolate.tool.queries` (`metric`, plus `tool`). Pinned: the policy is found only when `airlock` and
`revision` are both JSON strings and the revision is the served one; a refused call is the empty result
(`entity` `""`, `rule_version` `""` without a policy, the served one with it), never an error; the metric's
airlock is the served policy's, `none` without one, so a caller cannot invent a label; nothing is looked up or
queried for a refused call.

## rules/compensation.json, rules/vitals.json

Shared inputs, `fixture`:

- `entity`: what a tool plugin declares, as the manifest carries it: `name`, `from`, `row_key`, `subject` (defaults
  to the row key), `subject_name`, `period`, and `columns` in declaration order, each `name`, `expression`, `type`
  (`number` | `text`), and optionally `unit`, `unit_column`, `decimals`.
- `identity_attribute`: the plugin's name for the identity join's value. The asker's own rows are marked `self`
  only when it equals the entity's `subject` (`SqlCompiler.ownKey`).
- `rule_version`: what the served policy reports (the policy's revision).
- `table`: the Postgres table behind the entity (`name`, `columns` name → SQL type, `rows`), for the runner to
  create and seed.

`rule_sets`: rule-set files by a local name, in the same JSON the projects ship under `rules/`.

A case: `rule_set`, `principal` (`sub`, `email`, `roles`, and `attributes`: what the identity join yielded; the
runner adds `sub` and `email` to the attributes, as the token does), `query` (`action` `read` | `aggregate`, and
`args`: the raw arguments of the standard tools, `filters`/`limit` for a read, `function`/`column`/`group_by`/
`filters` for an aggregate), and `expect`, one of:

- `{"error": "Invalid arguments: …"}`: a tool error.
- `{"obligations": null, "result": {…}}`: denied: the empty result, no SQL runs.
- `{"obligations": {…}, "sql": "…", "params": […], "sql_hash": "…", "result": {…}}`: `obligations` as
  `all_rows`, `rows` (`column`, `value`), `hidden`, `masked`, `min_group_size`, `matched_rules` (in rule-set order);
  `result` the tool result JSON before `data_source` (`entity`, `row_count`, `columns`, `rows`, `rule_version`,
  `rules`, `sql_hash`, `labels`).

The order is the Java one and part of the contract: the SDK shapes the raw arguments inside the tool first (an
unknown `op` or `function` is an error even for a denied user), then the rules are evaluated (a denied user gets the
empty result even for a filter on a hidden column), then the arguments are checked against the columns the user may
see, then the statement is built. A runner without a database checks `error`, `obligations`, `sql`, `params` and
the result's `columns`, `rules` and `labels`; with Postgres (Testcontainers, `postgres:17`) it also runs `sql` with
`params` on the seeded table and compares `rows`.

## rules/rule-set-load.json

A case: `json` (the file's text) and `expect`: `{"valid": true}`, `{"error": "<exact message>"}`, or
`{"error_prefix": "Invalid rules JSON: "}` where the rest is the parser's own wording (syntax, a duplicate key, an
unknown key inside a rule, an unknown enum value). Pinned: unknown keys and duplicate keys refuse the file; the
row-filter grammar is exactly `true`, `<column> == principal.<attribute>` or `<column> == '<literal>'` with
`[a-z_][a-z0-9_]*` names and `[a-z0-9_-]+` literals (lower case only), surrounding whitespace allowed.

## sql-safety/compensation.json

Shared inputs: `fixture` (a path under `cases/` whose `fixture.entity` is used) and `obligations` presets. A case:
`obligations` (a preset's name), optional `self_key`, `query` as in the rules cases, and `expect`: `error`, or
`sql`, `params`, `sql_hash`, `columns`, `labels`. No rules are evaluated and nothing runs: this is the argument
boundary alone. Pinned beside the statement text: every value is a parameter; `ilike` without `%` or `_` becomes
`%value%`; a value for a text column is its string form (`42` → `"42"`, `true` → `"true"`, lower case as Java
prints it); a numeric column's value must parse as a decimal; `limit` defaults to 100, is capped to 100, floored to
1 and truncated to an integer, a string is refused; at most 10 filters; `min` and `max` are refused under a minimum
group size.

## errors/data-source.json

`texts`: each error's sentence for the model. A case: `failure`, built as the runtime's driver raises it (`sqlstate`
a database error with that SQLSTATE, `network` one of `refused`, `unknown_host`, `no_route`, `timeout` as the
socket's own exception wrapped in a driver error without a SQLSTATE, `cause` what it wraps, nothing at all an
error of the plugin's own), and `error`. A class-28 SQLSTATE anywhere wins: a pool's timeout wraps the login it last
tried.

## audit/

- `audit-event.schema.json`: every field, its type, the allowed values, and per event and outcome which fields are
  null or empty. Every field is always present and the keys go in the listed order: a runner checks the order of
  the line its runtime writes, not only the schema. `ts` has no fraction when its millisecond is 0
  (`2026-10-16T21:02:11Z`), as `java.time.Instant` prints it.
- `exchange.json`, `refusal.json`, `error.json`, `tool-error.json` (a call whose data source refused the login): one line each, valid against the schema and in wire order.
- `outcome.json`: a case is `answer` (the concatenated chunks, or `null`) and `failure` (the error code the exchange
  failed with, or `null`); `expect` is `outcome`, `reason` and the `answer` the line carries.
- `rows-hash.json`: a case is `rows` (as the tool result carries them), `canonical` (the exact text hashed) and
  `rows_hash`. Note the escaping: Jackson writes control characters other than `\t \n \r \b \f` as `\u00XX` with
  upper-case hex, which Python's `json.dumps` does not; the Python runtime must produce Java's text.
- `grounding-strip.json`: the chat answer's grounding in, the audit line's `grounding` out.

The runner for the airlock builds each event through its own audit code (for the examples: the event's fields; for
outcomes: an exchange that ends with that answer or failure) and compares the line it writes.

## delivery/fetch.json

Shared inputs: `artifact` (the bytes the digest names, UTF-8) and `token` (the content of the token file). A case:
`status` and `served` (what the runner's HTTP server answers to `GET`), `expect.loaded`, and when refused
`expect.error`, a fragment of the refusal's message. The runner writes `token` to a file, starts a local server that
checks `Authorization: Bearer <token>`, and fetches with the digest of `artifact`. Pinned: a 200 whose bytes hash
to the digest is the loaded file; other bytes refuse naming the digest they have; a 4xx refuses at once (only a
connection failure or a 5xx is retried); a refusal leaves no file behind.

## llm/catalog.json

Shared inputs: `base`, documents by file name. A case's `files` are merged over `base` by file name (`null` removes
the file); the runner writes each document as its own file (JSON is YAML) and loads the directory. `expect` is
either the catalog of `Airlock hr`: `llms` (`{id, provider, url, model}`, sorted by id), `main` (the id of its
main LLM, `null` while it names none) and `data_sources` (`{id, url, secretRef}`, sorted by id); or `problems`: the
loader's exact lines, compared as a set. Pinned: the levels are the Global, the airlock's Space and the airlock; a
nearer id replaces the farther entry whole; no Global reads as an empty one; one Global, named `aisolate`; an LLM
entry needs a valid id (once per list), provider `ollama` or `openai`, a url and a model, and may name its key's
Secret (`secretRef`); `spec.llm` may be absent (the airlock runs nothing until `aisolate deploy` brings it) and
must resolve when present.
The URL rules (no credential, a host) stay Java's alone, as for data sources.

## llm/errors.json

Shared input: `env`, the airlock pod's LLM env. A case's `env` is merged over it (`null` removes the variable) and
the runner reads the catalog as the pod does at its start: `expect.main` and `expect.llms` (in env order), or
`expect.start_error`, the exact message refusing the start. A call case points every `AISOLATE_LLM_<i>_URL`'s
scheme, host and port at a stub model server (its path kept: an `openai` entry's `/v1` stays) answering
`server.status` with `server.body`, calls `llm(call.llm)` then `embed` or `chat` with `call.input`, and checks
`expect.request` (the path; each listed body key with that value; each listed header, by lower-case name, with
that value, `null` for absent; `null`: nothing sent), then one of `result`, `error` (an `LlmError` /
`LlmException` with exactly that message) or `failure` (raises, but no `LlmError` / `LlmException`). The 501 and
400 bodies are Ollama's own (0.40.6, 2026-10-19); the `gpt` entry is OpenAI-compatible (provider `openai`, its key
`sk-test-1234`).

## Grounding

One file per template (`company-hr`, `company-sales`, `auditor`, `hospital`): 121 cases. The object is `template`,
`about`, `evidence[]` and `cases[]`.

- `evidence[]`, what the exchange gathered before the final turn, in `ev-n` order:
  - a tool result: `id` (`ev-n`), `tool`, `args`, `entity`, `columns[]`, `labels` (`row_key`, `subject`, `period`,
    `units{}`, `unit_columns{}`, `masked[]`, `function`, `facts[]`: the fact columns, absent when none), `rows` as an
    object of row handles (`{"r1": {col: value}}`,
    handles numbered `r1…rn` across the file);
  - a document: `id`, `source`, `passages[]` (`title`, `section`, `body`).
- `cases[]`: `name`, `error` (the error class the case exercises, `correct` for none;
  `typed_text_fact` only in `hospital.json`, the one template that marks fact columns), `question`, `answer` (the
  structured final turn: `text`, `figures[]`, `quotes[]`, `dont_know`), and what grounding must give:
  - `expect`: figure or table id → status (`sourced`, `computed`, `unsourced`, `mismatch`);
  - `reason` (optional): a substring some figure's reason contains;
  - `rendered` (optional): the exact answer text the user gets;
  - `quotes` (optional): quote id → `quoted` | `mismatch`.

Every runner also checks, on every case: `****` never appears in the rendered answer or in any figure's `display`,
and grounding the same input twice gives the same output. Numbers are exact decimals (Java
`USE_BIG_DECIMAL_FOR_FLOATS`, Python `parse_float=Decimal`). `GoldenSetTest` additionally checks that every
template covers every error class but `typed_text_fact`.

## Layer 2: black-box HTTP scenarios (planned, not implemented)

The same scenarios run against each image pair (`aisolate/airlock` + `aisolate/tool-server`, and
`aisolate/airlock-python` + `aisolate/tool-server-python`), each pod pair started with Testcontainers next to a
Keycloak with a test realm (users, roles, clients `acme-chat`, `partner-app`, `other-app`), a Postgres seeded
from `cases/rules/compensation.json`'s table plus the identity join's table, a stub LLM that answers from a script
(OpenAI/Ollama-compatible HTTP), a stub ingest that records what it receives and an OTLP receiver. The documents
and the project artifact are mounted as the controller mounts them.

1. An admitted question whose script calls `read_records` gets an answer with evidence: SSE `evidence`,
   `answer_chunk`, `verification`, `done` in order, the evidence event without rows.
2. The same with `Accept: application/json`: one document with exactly `answer`, `dont_know`, `segments`,
   `figures`, `tables`, `quotes`, `provenance`, `evidence`, `verification`, `revision`, `trace_id`.
3. No token, an unknown key, another audience: 401 with the `WWW-Authenticate` header; the model is never called.
4. A user neither list holds, a client the airlock does not list: 403 `forbidden`; one `refusal` line reaches the
   ingest with reason `user` / `client`; the model is never called.
5. Unknown airlock: 404; a blank message or one over 4000 characters: 400.
6. The ingest receives one `exchange` line per exchange, with the airlock's bearer token, valid against
   `cases/audit/audit-event.schema.json`, `trace_id` equal to the exchange's trace; the same line on stdout.
7. The ingest down and the buffer full: 503 `audit unavailable` before admission; the lines arrive in order once it
   is back.
8. `tools/list` offered to the model is the airlock's `tools` only; a scripted call to another tool is dropped
   and the model told so.
9. A tool call under a rule set that grants the user nothing: zero rows, the answer "I don't know", outcome
   `dont_know`.
10. A filter on a masked column: the tool error `Invalid arguments: column is not available: iban`, no SQL.
11. The tool server called directly with a `_meta` pinning another revision: the empty result.
12. No span (attributes, events, status) of either pod carries an e-mail, the question, the answer, SQL, a row
    value, a role or an exception message; span names and attributes as `docs/observability.md`.
13. Health (`/actuator/health/liveness`, `/actuator/health/readiness`) and metrics (`/actuator/prometheus`) only
    on the management port 9464, without a token; the metric names and labels of `docs/observability.md` after
    scenarios 1 and 4 (`aisolate.exchanges` by outcome, `aisolate.refusals` by reason, `aisolate.tool.queries` by
    outcome).
