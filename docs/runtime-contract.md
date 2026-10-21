# The runtime contract

What **both** runtimes implement: the Java pair (`apps/airlock`, `apps/tool-server`) and the Python pair
(`apps/airlock-python`, `apps/tool-server-python`). One behaviour, two languages: the Python runtime never admits
someone the Java runtime refuses, never shows a row or column the Java runtime hides, and writes the same audit
event, spans, metrics and answer format, or vice versa. Where this document and the Java code disagree, the Java code
is right and this document is the bug; where the conformance cases (`conformance/`) and either runtime disagree,
the runtime is the bug.

LLMs follow [llms-and-data-sources.md](llms-and-data-sources.md).

Every item names the Java class that implements it (paths relative to the repository root; `.../` is the module's
`src/main/java/com/reportix/aisolate/<module>/`). The project artifact a Python pod loads is
[`python-artifact.md`](python-artifact.md); the Python SDK is `libs/sdk-python/src/aisolate/`.

Contents: 1 pod inputs · 2 configuration loading · 3 the tool server · 3b how Java loads code · 4 airlock HTTP API ·
5 admission · 6 the agent loop · 7 audit trail · 8 tracing · 9 metrics · 10 airlock → tool server ·
11 how Python loads project code · 12 Python dependencies · 13 known divergences · 14 details easy to get wrong.

## 1. Pod inputs

Both pods of one airlock are rendered by `apps/controller/.../render/Renderer.java` (`render()`); the
settings it renders with are `ControllerProperties` (bound from the chart's `/etc/aisolate/settings.yaml`,
template `chart/templates/platform/controller/_settings.tpl`). A Python runtime reads **the same env
names and the same paths**: the controller renders one pod shape for both languages, only the image differs
(exception: the egress proxy, §1.6).

Names, for space `s`, airlock `a`:

| Object | Name | Source |
|---|---|---|
| airlock Deployment / Service / Ingress / NetworkPolicy | `s-a-airlock` | `Renderer.airlockPodName` |
| tool server Deployment / Service / NetworkPolicy | `s-a-mcp-server` | `Names.mcpServerName` |
| documents ConfigMap | `s-a-config` | `Renderer.configMapName` |
| code token Secret | `s-a-code`, key `token` | `Renderer.codeSecretName`, `AuditIngestToken.KEY` |
| audit token Secret | `s-a-audit`, key `token` | `Renderer.auditSecretName`, `AuditIngestToken.KEY` |

An airlock without `llm` or `prompt` renders no pod at all. The tool server pod exists only when the airlock has
code **and** `spec.tools` is non-empty (`Target.runsMcpServer`). The airlock pod fetches the code whenever the
airlock has any (its handlers); §1.4.1.

### 1.1 Containers

| | airlock pod | tool server pod |
|---|---|---|
| container name | `airlock` | `tools` |
| image | `settings.images().airlock(python)` (`Renderer.buildAirlockDeployment`): `aisolate/airlock`, `aisolate/airlock-python` when `spec.jar.language` is `python` | `settings.images().toolServer(python)` (`Renderer.buildMcpServerDeployment`): `aisolate/tool-server`, `aisolate/tool-server-python` |
| ports | `http` 8080, `management` 9464 | `http` 8081, `management` 9464 |
| Service | port 8080 → 8080 | port 8081 → 8081 |
| replicas | `platform.airlock.replicas` | 1 |
| strategy | `Recreate` | `Recreate` |
| resources | `platform.airlock.resources` | `platform.toolServer.resources` |

Every container (`Renderer.buildContainer`):
- readiness `GET /actuator/health/readiness` on 9464 (initialDelay 10 s, period 5 s, failureThreshold 30);
  liveness `GET /actuator/health/liveness` on 9464 (initialDelay 60 s, period 10 s). **The paths are fixed in the
  Renderer: a Python runtime serves exactly these** (200 `{"status":"UP"}`, 503 `{"status":"DOWN"}`), plus
  `GET /actuator/prometheus` on 9464, no token. Nothing else answers on 9464; none of these on 8080/8081.
- `securityContext`: `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, capabilities drop `ALL`.
- Pod (`Renderer.buildPod`): `runAsNonRoot: true` (the image must set a **numeric** non-root `USER`; the Java
  images use `10001:10001`, `apps/tool-server/Dockerfile`), seccomp `RuntimeDefault`,
  `automountServiceAccountToken: false`; every pod gets `platform.airlock.runtimeClassName` when set.
- Pod template annotation `aisolate.dev/revision: <revision>`; labels `aisolate.dev/role` (`airlock` |
  `tool-server`), `aisolate.dev/space`, `aisolate.dev/airlock`,
  `aisolate.dev/managed-by: controller`.

Read-only rootfs consequences for Python: set `PYTHONDONTWRITEBYTECODE=1`; everything written goes to `/tmp`
(emptyDir, **256 Mi cap**, `Renderer.TMP_SIZE_LIMIT`), including an extracted artifact.

### 1.2 Env, airlock pod (`Renderer.buildAirlockDeployment`)

| Env | Example | Meaning | When |
|---|---|---|---|
| `AISOLATE_AIRLOCK` | `hr` | the one airlock this pod serves; any other id is 404 | always |
| `AISOLATE_CONFIG_DIR` | `/etc/aisolate` | the mounted documents (§2) | always |
| `AISOLATE_CONFIG_REVISION` | `3f9c0a1b2c4d` | the revision the files must hash to (§2.2) | always |
| `AISOLATE_CODE_URL` | `http://backend.<ns>.svc:8085/v1/code/s/a/sha256:<64 hex>` | where the pod fetches its code (§1.4.1) | airlock has code, install has a backend |
| `AISOLATE_CODE_DIGEST` | `sha256:<64 hex>` | the code's pin, `spec.jar.digest`, case ignored | airlock has code |
| `AISOLATE_CODE_TOKEN_FILE` | `/var/run/aisolate/code/token` | the airlock's code token, the fetch's bearer | airlock has code, install has a backend |
| `AISOLATE_TOOL_SERVER_URL` | `http://s-a-mcp-server:8081/mcp` | the airlock's MCP server | tool server rendered |
| `AISOLATE_PUBLIC_URL` | `http://aisolate.localhost` | links and the 401 challenge | always |
| `AISOLATE_CORS_ALLOWED_ORIGINS` | `http://chat.aisolate.localhost` | comma-separated origin patterns; default `*` | always (may be blank → default) |
| `AISOLATE_API_DOCS` | `false` | OpenAPI docs off in the pod | always |
| `AISOLATE_LLM` | `granite` | the main LLM's id (`spec.llm`), one of the `_ID`s below ([LLMs and data sources](llms-and-data-sources.md#what-the-controller-renders)) | always |
| `AISOLATE_LLM_<i>_ID` / `_PROVIDER` / `_URL` / `_MODEL` | `nomic` / `ollama` / `http://ollama.aisolate.svc:11434` / `nomic-embed-text` | LLM i of the catalog (`Configuration.llmsOf`: global, space, airlock, nearer id wins), ids in sorted order, contiguous from 0; provider `ollama` or `openai`; no defaults | per LLM |
| `AISOLATE_LLM_<i>_API_KEY` | from Secret `secretRef`, key `apiKey` | an `openai` LLM's API key, the bearer of its requests ([API keys](llms-and-data-sources.md#api-keys)) | per LLM with a `secretRef` |
| `AISOLATE_DATASOURCE_<i>_ID` / `_URL` | `acme_hr` / `jdbc:postgresql://acme-db.aisolate.svc:5432/acme` | data source i (`Configuration.dataSourcesOf`, global ones included), ids in sorted order | per data source |
| `AISOLATE_DATASOURCE_<i>_USERNAME` / `_PASSWORD` | from Secret `secretRef`, keys `username` / `password` | its read-only role | per data source |
| `AISOLATE_ISSUER_URI` | `http://keycloak.aisolate.localhost/realms/acme` | token issuer (exact `iss`) | always |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` | `http://keycloak.keycloak.svc/realms/acme/protocol/openid-connect/certs` | JWKS override (in-cluster); without it, the issuer's discovery document's `jwks_uri` | chart sets one |
| `AISOLATE_ROLES_CLAIM` | `realm_access.roles` | dot path of the roles claim; that is the default | chart sets one |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://otel-collector.observability.svc:4318` | OTLP/HTTP **base** URL; append `/v1/traces` unless it already ends so; strip trailing `/` (`TracingConfig.otlpTracingConnectionDetails`). Unset: spans are still made (trace ids), exported nowhere | chart sets one |
| `JDK_JAVA_OPTIONS` | `-Dhttps.proxyHost=egress -Dhttps.proxyPort=3128 -Dhttp.nonProxyHosts=…` | egress proxy, Java only (§1.6) | proxy enabled |
| `AISOLATE_AUDIT_URL` | `http://backend.aisolate.svc:8085/v1/audit/events` | the backend's ingest | audit enabled |
| `AISOLATE_AUDIT_TOKEN_FILE` | `/var/run/aisolate/audit/token` | the airlock's ingest token | audit enabled |
| `AISOLATE_AUDIT_BUFFER_DIR` | `/var/lib/aisolate/audit` | unsent lines | audit enabled |
| `AISOLATE_AUDIT_FAIL_CLOSED` | `true` | refuse new questions while the buffer is full | audit enabled |
| `AISOLATE_AUDIT_STDOUT` | `true` | each line on stdout too | audit enabled |

Read with a default, never rendered (`apps/airlock/src/main/resources/application.properties`,
`AirlockProperties`): `AISOLATE_EXCHANGE_MAX_DURATION` (`8m`), `AISOLATE_MODEL_READ_TIMEOUT` (`90s`; connect
5 s), `AISOLATE_AUDIT_BUFFER_MAX` (`64MB`), audit defaults `buffer-dir=/var/lib/aisolate/audit`,
`fail-closed=true`, `stdout=true`. The airlock's token audience is fixed: `aisolate`.
`AISOLATE_CONFIG_DISCOVERY_DIR` is set only on the discovery pods (`Renderer.buildDiscoveryDeployment`), which
**always run the Java airlock image**: the Python airlock does not implement discovery mode.

Data source URLs are **JDBC URLs** (`jdbc:postgresql://host:port/db[?params]`): a Python runtime strips the
`jdbc:` prefix (as `Urls` does) and connects with the env username/password, never credentials from the URL.
Startup check, both pods (`DataSourceLogin.check`): `AISOLATE_DATASOURCE_<i>_ID is blank`,
`AISOLATE_DATASOURCE_<i>_URL is blank`, `data source <id> is defined twice` refuse the start. Indexes are
contiguous from 0; read until the first missing `_ID`. The airlock pod checks its LLM catalog the same way
([The runtimes](llms-and-data-sources.md#the-runtimes)): `AISOLATE_LLM is blank`, `AISOLATE_LLM_<i>_ID is blank`,
`…_URL is blank`, `…_MODEL is blank`, `model <id> is defined twice`, `model <id>: provider <p> is not one of [ollama,
openai]`, `AISOLATE_LLM granite is none of the AISOLATE_LLM_<i>_ID` refuse the start; `_API_KEY` is optional, a
blank one none.

### 1.3 Env, tool server pod (`Renderer.buildMcpServerDeployment`)

`AISOLATE_CONFIG_DIR`, `AISOLATE_CONFIG_REVISION`, `AISOLATE_CODE_URL`, `AISOLATE_CODE_DIGEST`, `AISOLATE_CODE_TOKEN_FILE`,
`AISOLATE_DATASOURCE_<i>_*`, `AISOLATE_ISSUER_URI`, `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`,
`AISOLATE_ROLES_CLAIM`, `JDK_JAVA_OPTIONS`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`, as above. Not rendered, default:
`AISOLATE_AUDIENCE` (`aisolate`; `apps/tool-server/src/main/resources/application.properties`). No
`AISOLATE_AIRLOCK`, no `AISOLATE_LLM*`, no audit: the tool server serves every airlock in its documents (one, in a pod).

### 1.4 Mounts

| Volume | Path | Mode | Content | Pods |
|---|---|---|---|---|
| `config` (ConfigMap `s-a-config`) | `/etc/aisolate` | ro | `Global-aisolate.yaml`, `Space-s.yaml`, `Airlock-a.yaml` (§2) | both |
| `code-token` (Secret `s-a-code`) | `/var/run/aisolate/code` | ro | file `token` | both, when code and a backend (`Renderer.buildCodeWiring`) |
| `tmp` (emptyDir 2 Gi; 256 Mi on the discovery pod) | `/tmp` | rw | scratch: the fetched code, a Python pod's extracted artifact | both |
| `audit-token` (Secret `s-a-audit`) | `/var/run/aisolate/audit` | ro | file `token` | airlock, audit enabled |
| `audit-buffer` (emptyDir 96 Mi) | `/var/lib/aisolate/audit` | rw | unsent audit lines | airlock, audit enabled |

The audit token is `base64url_nopad(HMAC-SHA256(ingestKey, "s/a"))` (`AuditIngestToken.of`); the pod only
reads the file, it never derives the token. An `openai` LLM's API key is no file: it reaches the airlock pod as
`AISOLATE_LLM_<i>_API_KEY` from its Secret, as a data source's password does
([API keys](llms-and-data-sources.md#api-keys)); an `ollama` one has none.

### 1.4.1 The code: fetched at start (`libs/plugins` `CodeFetch`; Python `aisolate_runtime.artifact.fetch`)

No code object reaches the cluster: the backend keeps the artifact (a jar, or a Python zip) and serves it on its
cluster-internal ingest port 8085, never on the API or the ingress. A pod whose airlock has code, before it
serves anything:

1. reads the bearer from `AISOLATE_CODE_TOKEN_FILE`: `base64url_nopad(HMAC-SHA256(ingestKey, "code/s/a"))`
   (`AuditIngestToken.ofCode`), which admits this airlock's code alone and no audit line;
2. `GET AISOLATE_CODE_URL` (`<backend>/v1/code/s/a/<digest>`). A connection failure, a 5xx or a 429 is
   retried, 6 attempts with 1, 2, 4, 8, 16 s between them; any other non-200 refuses at once:
   `fetching the code from <url> answered <status>` (401 no token, 403 a token not this airlock's, 404 the
   airlock holds other code now);
3. streams the body to its scratch directory (Java `/tmp/aisolate/code/aisolate-code.jar`), hashing as it
   writes; the rootfs stays read-only. Bytes other than the digest refuse:
   `the code fetched from <url> has digest <d>, not AISOLATE_CODE_DIGEST <expected>`. A refusal leaves no file;
4. loads it as before, pinned by that digest (§3b; Python §11).

Any refusal fails the start (Java: the context does not start; Python: exit 1), and readiness is never UP before
the code is loaded; Kubernetes restarts the pod. A digest without `AISOLATE_CODE_URL` (an install without the
backend, `headless=true`) refuses: `the airlock has code (AISOLATE_CODE_DIGEST) but no AISOLATE_CODE_URL to fetch
it from: code needs the backend (headless=false)`. The cases both runtimes pass: `conformance/cases/delivery/
fetch.json`. Without `AISOLATE_CODE_DIGEST`, a local run or a test may still hand a pod a directory
(`AISOLATE_PLUGINS_DIR`, pinned by `AISOLATE_PLUGIN_DIGESTS`); nothing renders those in a cluster.
### 1.5 Network (`Renderer.buildAirlockPolicy`, `buildMcpServerPolicy`)

- airlock pod ingress: the ingress controller on 8080 (Ingress path prefix
  `/v1/spaces/s/airlocks/a/`, nginx `proxy-read-timeout: 600`, `proxy-buffering: off`), scrapers
  labelled `aisolate.dev/scraper=true` (any namespace) on 9464.
- airlock pod egress: DNS; every catalog LLM URL's, the OTLP endpoint's and each data source URL's host (admin-mapped
  peers); the tool server pod on 8081; the backend pods (`app=backend`) on **8085 only** (audit, code); the identity
  provider's peer; the egress proxy (`aisolate.dev/role=egress-proxy`, port 3128) when enabled.
- tool server ingress: only its own airlock pod on 8081; scrapers on 9464. Egress: DNS, data source
  hosts, OTLP, the backend pods on **8085 only** when the airlock's code is fetched (§1.4.1), identity provider
  (JWKS), proxy.

### 1.6 Egress proxy (Envoy, `buildProxyEnv`)

Rendered as `JDK_JAVA_OPTIONS`, which a Python process ignores. The proxy is host `egress`, port 3128; the
non-proxied hosts are the admin's mapped hosts, the IdP's issuer and JWKS hosts (unless proxied),
`localhost`, `*.svc`, `*.cluster.local`. A Python airlock's pods get, in addition,
`HTTPS_PROXY=http://egress:3128` and `NO_PROXY=<same list, comma-separated, `*.svc` written `.svc`>`;
httpx2 honours both (`trust_env=True`, its default).

## 2. Configuration loading

### 2.1 The documents (`libs/config`)

`ConfigurationLoader.load(dir, revision)`: every `*.yaml` / `*.yml` directly in `AISOLATE_CONFIG_DIR`, name
order; each file a YAML stream, each object one document. Blank dir: refuse the start
(`AISOLATE_CONFIG_DIR is required: the directory of the release's documents`). Strict parse: unknown key
refused (`FAIL_ON_UNKNOWN_PROPERTIES`), duplicate key refused. A document whose `apiVersion` is not
`aisolate.dev/...` is skipped; one under `aisolate.dev/` must be exactly `aisolate.dev/v1`. Kinds `Global` (at
most one, named `aisolate`; missing = empty), `Space`, `Airlock`; anything else refused. Any problem → `ConfigurationException` listing all of them →
the pod does not start.

Fields read at runtime (records `GlobalDocument`, `SpaceDocument`, `AirlockDocument`,
`Llm`, `DataSource`); an LLM is `{id, provider, url, model}`, no type
([LLMs and data sources](llms-and-data-sources.md)):

```yaml
apiVersion: aisolate.dev/v1
kind: Global
metadata: {name: aisolate}
spec:
  llms: [{id: granite, provider: ollama, url: http://…, model: "granite4.1:8b"}, {id: nomic, …}]
  dataSources: [...]
---
kind: Space
metadata: {name: acme}
spec:
  displayName: Acme
  description: …
  access: {users: [elena@acme.example]}          # e-mails, case ignored
  llms: [{id: granite, provider: ollama, url: http://…, model: "granite4.1:8b"}]   # optional
  dataSources: [{id: acme_hr, url: jdbc:postgresql://…, secretRef: acme-acme-hr}]
---
kind: Airlock
metadata: {name: hr}
spec:
  space: acme
  displayName: HR
  description: …
  access: {users: [...], callers: [aisolate-chat], clients: [etl]}   # callers = azp values, "*" any; clients = machine azp values, exact, absent when none
  preLLM:
    contextPreparator: {handlers: [PolicyContext]}
    conversationMemory: {handlers: []}
  llm: granite                                      # the main LLM's id; absent until the first deploy, and then the airlock runs nothing
  prompt: "…"                                       # absent until the first deploy likewise
  tools: [find_employee]
  postLLM:
    answerVerifier: {handlers: [FiguresInEvidence]}
  llms: [...]                                       # its own; optional
  dataSources: [...]
  jar: {digest: "sha256:<hex>", tools: [find_employee], language: python}   # null before the first deploy; language absent for a jar
```

Resolution (`Configuration`):
- `accessOf(airlock)`: users = space users ++ airlock users, distinct, list order; callers and clients = the
  airlock's.
- `llmsOf(airlock)`: the Global's, space's, then airlock's `llms` by id, a nearer same id replacing
  the farther; sorted by id. `mainLlmOf(airlock)`: its entry with the id `spec.llm`.
- `dataSourcesOf(airlock)`: the Global's, space's, then airlock's; a deeper same id replaces the
  shallower. The controller renders them sorted by id.
- Handler lists: `contextPreparatorHandlers()`, `memoryHandlers()`, `verifierHandlers()`; tools = `spec.tools`.

Validation the pods repeat (`Documents.validateReferences`): slugs (`Names.SLUG`
`^[a-z]([a-z0-9-]{0,39}[a-z0-9])$`), data source id
(`Names.DATA_SOURCE_ID`), at least one caller, user
lists are e-mails, machine clients none blank, repeated, space-containing or `*` (`Names.clientProblems`),
no blank/duplicate/space-containing names in tools/handlers, tools/handlers only with `jar`, every `tools` entry in `jar.tools`, each LLM entry with an id, provider `ollama` or `openai`, a url and a
model (an optional `secretRef`), the
airlock's `spec.llm` resolving over the three levels when it is set (an airlock without `llm` or `prompt` is valid
and runs nothing). The Python
runtime may trust what the controller rendered (the controller and backend ran the same checks), but it must
refuse to start on an unparsable document, a dangling reference, or a revision mismatch: **no partial
configuration is ever served.**

### 2.2 The revision check (`ConfigurationLoader.revision`, `Configuration.declaredAs`)

revision = first 12 hex of SHA-256 over every document sorted by (kind, name), each fed as
`<kind>/<name>` + `\0` + `canonical(document)` (UTF-8). The ConfigMap files **are** the canonical form
(`Renderer.buildFiles` writes `ConfigurationLoader.canonical`), one document per file, so a Python runtime
computes it over the **raw file bytes** keyed by each file's own `kind`/`metadata.name`; it never re-serialises
YAML. Kind order is string order: `Airlock` < `Global` < `Space`. Declared non-blank and different:
refuse with `revision mismatch: AISOLATE_CONFIG_REVISION says <x>, the documents hash to <y>`. Conformance pins
this with a fixture directory and its expected revision.

### 2.3 Tokens and the Principal (`libs/identity`: `SecurityConfig`, `Principal`, `IdentityProperties`)

Validation (Spring resource server, both pods): signature against the JWKS (keys by `kid`, the algorithm the
JWK names; Keycloak: RS256; never `none` or HS*), `iss` == `AISOLATE_ISSUER_URI` exactly, `exp`/`nbf` with 60 s
skew, `aud` contains `aisolate` (an empty audience setting refuses the start: `SecurityConfig.requireAudience`).
Bearer from `Authorization: Bearer <jwt>` only.

`Principal.of(claims, rolesClaim[, identityAttribute, identityValue])`:
- `sub` required and non-blank, else the token is unusable (`token has no sub claim`).
- `email` = claim `email` if a string, else null. `email_verified` is not consulted.
- `azp` = claim `azp` if a string, else null.
- roles = walk the dot path (`realm_access.roles`) through objects; a list keeps its string items, a single
  string is one role, anything else none.
- `attributes`: `sub`, `email` (when present), and the identity join's value under the plugin's
  `identityAttribute` (tool server only, when the join found a row).

Admission (`Principal.admissionFailure(users, callers, clients)`), the same in both pods: admitted when the e-mail
is null, `azp` non-null and `clients` contains `azp` (exact, case-sensitive, `*` no wildcard; callers not
consulted); else `USER` when the e-mail is null or
equals no listed user ignoring case (Java `String.equalsIgnoreCase`; Python `a.lower() == b.lower()`,
identical for the ASCII e-mails `Names.userProblems` admits); else `CLIENT` unless `azp` is non-null and callers contain `*` or
`azp`; else admitted. Roles play no part in admission.

401 from the airlock (`SecurityConfig.apiSecurity`): status 401, `WWW-Authenticate: Bearer
resource_metadata="<AISOLATE_PUBLIC_URL>/.well-known/oauth-protected-resource"`, `Content-Type:
application/problem+json`, body `{"status":401,"title":"Unauthorized"}`. Unauthenticated paths:
`/actuator/health/**`, `/actuator/prometheus`, `/.well-known/**` (RFC 9728 document: `resource` = public URL,
`authorization_servers` = [issuer], `scopes_supported` = [`aisolate`], `resource_name` = `AIsolate`,
`tls_client_certificate_bound_access_tokens` = false). CORS on `/v1/**` and `/.well-known/**`: the configured
origin patterns, methods GET POST PUT OPTIONS, headers Authorization Content-Type Accept If-Match, max-age 3600.

## 3. The tool server

### 3.1 MCP endpoint (`apps/tool-server/.../application/GovernedToolsConfig.java`, `application.properties`)

- `POST /mcp` on 8081, MCP streamable HTTP, **stateless** (Spring AI `protocol=STATELESS`): no
  `Mcp-Session-Id`, no server-initiated messages, each request carries its bearer; responses are
  `application/json` (no SSE stream). Server info `aisolate-tool-server` / `0.1.0`. Python: the official MCP SDK
  with `stateless_http=True, json_response=True`.
- Every request needs a valid token (§2.3, audience `AISOLATE_AUDIENCE`); otherwise 401 with
  `WWW-Authenticate: Bearer…` and no body (`infrastructure/SecurityConfig.mcpSecurity`). The airlock turns any
  401 into `the tool server rejected the request`, anything else into `the tool server could not be reached`
  (`McpSyncToolClient.asFixedMessage`).
- `initialize`, `tools/list`, `tools/call` accepted with any valid token. `tools/list` is **not** admission-gated:
  it advertises, sorted by name, every tool some served airlock lists in `tools` and some plugin publishes
  (`PluginCatalog.listPublishedTools`); a listed name no plugin publishes is left out. Each entry
  `{name, description, inputSchema}` exactly as the plugin declared them. One page, no cursor. The airlock lists
  once per pod life and caches it.
- `tools/call` `params`: `name`, `arguments` (null → `{}`), `_meta: {airlock, revision}`; each pin is
  read only if a JSON string (`GovernedToolsConfig.readPin`). A name not listed: the SDK's JSON-RPC error.
- The traceparent/tracestate headers the airlock sends continue its trace.

### 3.2 One call, in order (`domain/GovernedQueryService.call`)

1. Span `governed query <tool>` (observation `aisolate.trace.query`), attributes `aisolate.airlock`,
   `aisolate.revision`, `aisolate.tool` (each only when non-empty).
2. Policy = `PolicySource.findPolicy(airlock, revision)`: empty when any pin is missing, the revision is
   not this pod's, or no such airlock (`domain/PolicySource`). No policy → `Result.empty("", "")`.
3. Gate 2 again: `Principal.of(claims).admissionFailure(policy.access.users, policy.access.callers,
   policy.access.clients)` must be empty
   **and** `tool ∈ policy.tools` (the airlock's `spec.tools`). Otherwise `Result.empty("", "<revision>")`.
   No DB work happens before this point.
4. Plugin = the one publishing the tool; span attribute `aisolate.data_source`. DB = the plugin's data source
   (none when the plugin has no entities).
5. Identity join (`resolveIdentity`): when the plugin has `identityJoin`, a DB and the token has `email`: run
   the plugin's SQL with the e-mail as its one parameter, first column of the first row (none → null).
   Principal rebuilt with `attributes[identityAttribute] = value`.
6. The handler runs with a GovernedQuery bound to (policy, principal, plugin, DB) and the arguments; it must
   return a `Result`, else `IllegalStateException` → generic error.
7. Outcome: `rows` (row_count > 0), `denied` (no rule matched: `rules` empty), `empty`; `invalid` on an
   `IllegalArgumentException`; `error` otherwise. Span attributes `aisolate.entity`, `aisolate.rows`,
   `aisolate.outcome`. Counter `aisolate.tool.queries` (Prometheus `aisolate_tool_queries_total`), labels
   `airlock` (the policy's, `none` without one: a caller cannot invent a label), `tool`, `outcome`.

A denial is always an **empty result, never an error**. The model cannot tell "not allowed" from "nothing there".

### 3.3 Results and errors (`GovernedToolsConfig.call`, `addDataSource`)

Success: one `TextContent` holding the `Result` as snake_case JSON, nulls included, plus `data_source` (the
publishing plugin's `dataSource`, null when none; added even to an empty result):

```json
{"entity": "ehr.medications", "row_count": 1,
 "columns": ["patient", "patient_id", "national_id", "medication_id", "drug", "dose", "frequency", "started_on"],
 "rows": [{"patient": "Julian Marek", "patient_id": 17, "national_id": "****", "medication_id": 41,
           "drug": "Apixaban", "dose": "5 mg", "frequency": "twice daily", "started_on": "2026-09-30"}],
 "rule_version": "3f9c0a1b2c4d", "rules": ["cardiology-care"], "sql_hash": "a1b2c3d4e5f6",
 "labels": {"row_key": "medication_id", "subject": "patient", "period": "started_on",
            "units": {}, "unit_columns": {}, "decimals": {},
            "masked": ["national_id"], "function": null, "facts": ["drug", "dose", "frequency"]},
 "data_source": "ehr"}
```

`labels.facts`, the readable columns marked as facts in column order, is the one key written only when it has a
column; an aggregate's labels carry none.

Empty: `{"entity":"","row_count":0,"columns":[],"rows":[],"rule_version":"","rules":[],"sql_hash":"","labels":null,"data_source":"…"}`
(entity and rule_version as in §3.2). `labels` is null for a `Result` a tool built itself without labels.

Row values: integers and numerics as JSON numbers (exact decimal digits, no float rounding), text as strings,
booleans as booleans, NULL as null, `date` as `"YYYY-MM-DD"`, timestamps as ISO-8601 with offset. Templates cast
anything else to text in the column expression so both runtimes print the same thing.

Errors, `isError: true`, one `TextContent`:
- `IllegalArgumentException` (argument checks, §3.6, and a handler's own): `Invalid arguments: <message>`; the
  model sees this message only.
- anything else: a fixed sentence by what failed, and `_meta` `{"error": <error>, "data_source": <the tool's
  plugin's data source>}` for the airlock: a SQLSTATE of class 28 anywhere in the exception's causes is
  `login_refused` (`The data source refused the login`); else one of class 08 or a network failure (refused,
  unknown host, no route, a connect or socket timeout) is `unreachable` (`The data source could not be reached`);
  else `failed` (`The tool could not complete the request`). The exception is logged, never sent (a driver
  message embeds the statement). `ToolError.of`; `conformance/cases/errors/data-source.json`.

### 3.4 The rules engine (`domain/rules/RulesEvaluator.evaluate`, `libs/config/.../rules/{RuleSet,Rule,RowFilter}`)

Rule set file (`RuleSet.parse`): JSON object with exactly the keys `entity` (required, non-blank),
`classification`, `rules`; strict (unknown keys, duplicate keys refused). Rule (`Rule`): `name`, `principals:
{role: <role>}` (the only selector; required, non-blank), `actions: [read|aggregate]` (non-empty), `effect:
allow|deny` (required), `row_filter` (required on allow, forbidden on deny), `columns: {<col>: hide|mask}`,
`aggregation: {min_group_size: n≥1}`; a deny takes no row_filter, columns or aggregation. Load errors fail the
start naming the file: `aisolate/rules/<file>: <message>`.

Row filter grammar (`RowFilter.parse`, regex exact):
`^\s*([a-z_][a-z0-9_]*)\s*==\s*(?:principal\.([a-z_][a-z0-9_]*)|'([a-z0-9_-]+)')\s*$`, or `true` (trimmed).

Evaluation for (principal, rule set of the entity, action):
1. matched = rules with `principals.role` ∈ principal.roles and action ∈ actions.
2. None matched, or any matched is a deny → **deny** (no obligations).
3. For each matched allow, in file order: `true` → all rows; else value = the literal, or
   `principal.attributes[attr]`; a null value → **deny the whole query** (fail closed); else add predicate
   `column = value`. Columns: `hide` → hidden, `mask` → masked. `min_group_size` = max over matched rules.
   Rule names collected in order (`rules` of the result).
4. masked := masked − hidden.
5. Row predicates are OR-ed (union of rows); `all rows` from any rule drops them.

No rule set for the entity, or the entity not declared by the calling tool's plugin → empty result (logged as a
warning naming airlock/entity/plugin).

### 3.5 SQL generation (`domain/sql/SqlCompiler`, `CompiledQuery`)

Identifiers come only from the entity (`from`, column `expression`s); every value is a bound parameter. The
canonical SQL uses `?` placeholders; **`sql_hash` = first 6 bytes (12 hex) of SHA-256 of that `?` text**, so both
runtimes hash identical text. A Python runtime renumbers each `?` outside quotes → `:p1..:pn` (pg8000 native) after hashing. Entity
expressions must therefore contain no `?` (pgjdbc has the same constraint).

READ (`compileRead`):
```
SELECT [(<subject expr> = ?) AS self, ]<col: expr AS name | '****' AS name for masked>, … FROM <from>
[WHERE <rows> AND <filters>] ORDER BY [self DESC, ]<row key expr> LIMIT ?
```
- columns = entity columns in declaration order minus hidden; `self` first when the selfKey is non-null.
- selfKey (`SqlCompiler.ownKey`) = the principal's identity value **only if** `entity.subject` equals the
  plugin's `identityAttribute`; else no `self`.
- `<rows>` = `(<expr> = ? OR <expr> = ?…)` unless all rows; each filter `(<expr> <op> ?)`; joined by ` AND `.
  Ops: eq `=`, neq `<>`, gt `>`, gte `>=`, lt `<`, lte `<=`, ilike `ILIKE` (value wrapped `%v%` unless it already
  contains `%` or `_`).
- limit = clamp(limit, 1, 100).
- labels (`readLabels`): readable = visible − masked; `row_key`/`subject` (= `subjectName`)/`period` only when
  readable; `units`, else `unit_columns` when the unit column is readable; `decimals`; `masked` list;
  `function` null; `facts` = the readable columns marked `fact`, in column order (left out of the JSON when empty).

AGGREGATE (`compileAggregate`):
```
SELECT [<group expr> AS <group>, ]<fn>(<col expr>) AS value, count(*) AS n FROM <from>
[WHERE …] [GROUP BY <group expr>] [HAVING count(DISTINCT <subject expr>) >= ?] LIMIT ?(=100)
```
- fn ∈ sum, avg, count, min, max (lower case). Under a min group size, `min`/`max` refused:
  `function MIN is not allowed here; use sum, avg or count`.
- HAVING counts distinct **subjects** (people), not rows; applies with or without GROUP BY.
- columns `[group,] value, n`; labels per `aggregateLabels` (`function` = fn name, group as row key, units/decimals
  of the aggregated column for `value` except for count; no `facts`).

### 3.6 Argument checks (`domain/sql/ToolArgs`), all `IllegalArgumentException`

queryable = entity columns − hidden − masked (`Entity.queryableColumns`): filters, group-by and the aggregated
column must be queryable (`column is not available: <name>`). At most 10 filters (`at most 10 filters`); `op is
required`; ilike only on TEXT (`op ilike needs a text column, got: <c>`); a value is required (`value is required
for filter on <c>`); TEXT values become strings, NUMBER values decimals (`value for numeric column <c> is not a
number: <v>`). Aggregate: `function is required`; non-count needs NUMBER (`SUM needs a numeric column, got: <c>`).
SDK-side parsing (`libs/sdk/.../Arguments`): `filters must be a list`, `each filter must be an object`, `<field> is
required`, `unknown op: <x>` / `unknown function: <x>` (case-insensitive), `limit must be a number`.

### 3.7 Database connections (`infrastructure/DataSourceConfig`)

One pool per data source id, from env (§1.2); read-only whatever the role allows (Java: `readOnly` +
`readOnlyMode=always`; Python: session `default_transaction_read_only = on`); connect timeout 5 s; statement
timeout 12 s (Python: server setting `statement_timeout = 12000`); socket timeout 15 s. A plugin with entities
whose data source this pod lacks fails the start: `plugin <p> reads data source <id>, which this airlock does not
define` (`PluginsConfig.checkDataSources`). No plugin at all fails the start (`PluginsConfig.checkPlugins`).
The tool server has no connection to AIsolate's own database.

Span per statement: `sql <data source>` (observation `aisolate.trace.sql`), attributes `aisolate.airlock`,
`aisolate.revision`, `aisolate.data_source`, `aisolate.entity`. Never a row, a value or the
statement.

### 3.8 The catalog check (`libs/plugins/.../CatalogCheck.check`), at deploy and at start

Refused, first problem named: a blank plugin/tool/entity name; a tool published twice; an entity declared twice;
a plugin with entities and no data source; an entity whose row key or subject is not a column; a column name not
matching `[a-z_][a-z0-9_]*`; two rule sets for one entity; a rule naming (in `columns` or its row filter) a column
its entity does not declare. Entity construction (`libs/sdk/.../Entity`) also refuses: a column keyed under
another name, `subjectName`/`period`/a `unitColumn` that is not a column; `subject` defaults to the row key. Column
construction (Java `Column`, Python `Column.__post_init__`) refuses negative decimals and a NUMBER column marked `fact`
(`column <name> is a number: only a text column can be a fact`), whichever entity it is declared in.

## 3b. How project code is loaded today (Java)

| Step | Where |
|---|---|
| CLI build: `gradlew build --console=plain` in the project; the jar is `build/libs/<rootProject.name>.jar`, else the one jar there | `apps/cli/.../GradleBuild.build`, `BuildCommand.build` |
| Rules packaged at `aisolate/rules/<entity>.json` (template `processResources { from("rules") }`) | `apps/cli/src/main/templates/*/build.gradle.kts` |
| CLI deploy: `ProjectFile.read` (aisolate.yaml, `.env`), `resolveVariables`, sign-in, build, `DeployClient.deploy` → `POST /v1/spaces/{space}/deploy` multipart: file part `jar`, text `documents` (aisolate.yaml), text `variables` (JSON object of `${NAME}` values) | `DeployCommand.call`, `DeployClient` |
| Backend: `DeployController.deploy` → `DeployService.deploy`: `readUpload` (`jar: required`; the part to a temp file, never held in memory), `JarInspector.read(path)` (**loads the classes in the backend**), `DocumentForms.scan`, `save` in one transaction, then `ClusterSyncService.sync` | `apps/backend/.../application/DeployController`, `domain/deploy/DeployService`, `JarInspector` |
| `JarInspector.Contents{digest, size, tools[{name, description, plugin, data_source}], handlers[{name, stage, order, description}], rules[{entity, rules_count}]}`; the bytes in `airlock_code` (1 MiB chunks by `seq`), the rest in `airlocks.jar_digest`, `jar_size`, `jar_tools`, `jar_handlers`, `jar_rules` (`V1__baseline.sql`) | `AirlockRepository.Code` |
| Document: `spec.jar = {digest, tools}`, plus `language: python` for a Python zip (`DeployController`'s `language` part; `ManifestReader` instead of `JarInspector`) | `apps/backend/.../domain/airlock/Documents.java`, `domain/deploy/ManifestReader` |
| Warnings: a code tool not in `tools`, a handler listed nowhere | `DeployService.warnings` |
| Cluster: no code object; the pods fetch the artifact from the backend's ingest port by digest (§1.4.1) | `CodeFetch`, the backend's code endpoint |
| Pods: `JarDigests.pinnedJars(dir, digests)` over the fetched file: every `*.jar` in the dir must hash (`sha256:<hex>` of the bytes, streamed) to a pin, else `plugin jar <f> has digest <d> which is not in AISOLATE_PLUGIN_DIGESTS` | `libs/plugins/.../JarDigests` |
| Scan: every class, parent-first over the runtime's SDK; a concrete class implementing `ToolPlugin` is a plugin, one implementing `ContextPreparator`/`ConversationMemory`/`AnswerVerifier` a handler (name = simple class name, order = its `order()`, description = `@Description`); both or two stages refused; no-arg constructor | `JarScan`, `HandlerCandidate` |
| Rules: every `.json` directly under `aisolate/rules/` (subfolder refused: `…: a rule set is rules/<entity>.json`; > 1 MiB refused) | `JarScan.ruleFiles`, `RuleSetFiles.discover` |
| Tool server: `PluginLoader.load` → `PluginCatalog` (runs `CatalogCheck`) → `checkPlugins`, `checkDataSources` | `apps/tool-server/.../infrastructure/PluginsConfig` |
| Airlock: `JarScan.handlersOf` per jar (no plugin constructors), then each served airlock's handlers resolved against its own jar digest; a listed handler the jar lacks fails the start | `apps/airlock/.../infrastructure/plugins/PluginsConfig`, `HandlerChain.resolve` |

Python plugs in at: the CLI build step (manifest + zip instead of Gradle), the backend's `readUpload` /
`JarInspector.read` (read `aisolate-manifest.json` + `rules/*.json` from the zip, never import), the fetch (§1.4.1,
the same endpoint and checks), the pods' pin check (same `sha256:` of the artifact bytes), and the runtime's loader (import the package from the artifact, then check it equals the manifest).

## 4. Airlock HTTP API

Paths below are relative to the repository root. Port **8080** (API), **9464** (management). Every response of an
airlock pod carries `x-aisolate-revision: <revision>` (`RevisionHeaderFilter`). The Swagger/OpenAPI routes are off
in pods (`AISOLATE_API_DOCS=false`); the Python airlock serves none.

| Route | Auth | Behaviour | Java |
|---|---|---|---|
| `POST /v1/spaces/{spaceId}/airlocks/{airlockId}/chat` | bearer | The chat exchange, below | `apps/airlock/.../application/ChatController.java` |
| `GET /.well-known/oauth-protected-resource` | none | RFC 9728 document: `{"resource": AISOLATE_PUBLIC_URL, "authorization_servers": [AISOLATE_ISSUER_URI], "scopes_supported": ["aisolate"], "resource_name": "AIsolate", "tls_client_certificate_bound_access_tokens": false}` | `libs/identity/.../SecurityConfig.java` |
| `GET /v1/airlocks` | bearer | Discovery. Served by the **discovery pods**, which stay Java (same image, `AISOLATE_CONFIG_DISCOVERY_DIR` set). The Python airlock does not implement it | `AirlockController`, `AirlockAdmissionService.listAdmittedAirlocks` |
| any other path | — | No token: 401 (below). With a valid token: 404 problem `unknown airlock` for another space/airlock on the chat path; other paths 404 | `GlobalExceptionHandler` |
| `GET :9464/actuator/prometheus` | none | Prometheus text format (§9) | `application.properties` (`management.*`) |
| `GET :9464/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/health` | none | `200 {"status":"UP"}` / `503 {"status":"DOWN"}`. The controller probes readiness and liveness on 9464 (`apps/controller/.../render/Renderer.java` ~l.889) | Spring Boot actuator |

There is **no `/readyz` on the airlock pod** (that is the backend's). Readiness = the airlock is loaded (documents
read, code imported, handlers resolvable); liveness = the process serves.

**CORS** (`SecurityConfig.corsConfigurationSource`): on `/v1/**` and `/.well-known/**`; allowed origins =
`AISOLATE_CORS_ALLOWED_ORIGINS` (comma list of origin *patterns*, default `*`); methods `GET, POST, PUT, OPTIONS`;
request headers `Authorization, Content-Type, Accept, If-Match`; exposed `AIsolate-Cluster`; max-age 3600;
credentials not allowed. Preflight `OPTIONS` needs no token.

### 4.1 Request

```
POST /v1/spaces/acme/airlocks/hr/chat
Authorization: Bearer <user token>
Content-Type: application/json
Accept: text/event-stream | application/json
{"message": "What is my gross salary?"}
```

- `message`: string, required, not blank (whitespace-only is blank), at most **4000** characters
  (`ChatController.MAX_QUESTION_LENGTH`). Otherwise **400** problem `message is blank or too long`. Unknown
  body fields are ignored. A body that is not JSON / wrong content type: 400 (Spring's default; detail free).
- **Content negotiation**: the JSON document only when the `Accept` header is **exactly** `application/json`
  (after trimming; no list, no `*/*` in it). Anything else — absent, `*/*`, `text/event-stream`, a list — is the
  SSE stream (`ChatController` comment, `docs/api.md` "The chat endpoint").
- Order of checks: token (401) → body validation (400) → audit accepting (503) → airlock lookup (404) →
  admission (403) → exchange. Validation happens before admission (Spring binds `@Valid` before the method runs),
  so a 400 writes no audit line.

### 4.2 SSE stream

Headers: `Content-Type: text/event-stream;charset=UTF-8`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`;
status 200, flushed **before** the model is asked (`SseStream.open`). Each frame:

```
event: <type>\ndata: <one-line JSON with a "type" field equal to <type>>\n\n
```

JSON key order as listed (Jackson record order, `type` last). Events in order: `tool_call`/`evidence` (or
`tool_error`) pairs
(passages' `evidence` frames come first, during context preparation), then `answer_chunk`, `verification` (only
if the airlock lists ≥1 verifier), `done`; or `error` instead of `answer_chunk…done`. A client that disconnects
stops writes; the exchange still runs to its audit line. (`ChatEvent.java`)

| type | data fields |
|---|---|
| `tool_call` | `id` (`call-n`, numbered per exchange from 1, counting every resolved or unknown tool call attempt), `tool`, `args` (object as the model sent it) |
| `evidence` | `id` (`ev-n`), `call_id` (`call-n`; `null` for passages), `tool` (tool name; for passages the handler's `source` string), `entity` (entity name; passages: `source` again), `row_count` (int; passages: number of passages), `columns` (string[]; passages: `["title","section"]`), `rule_version` (passages `""`), `rules` (string[]; passages `[]`), `sql_hash` (passages `""`), `data_source` (string or `null`). **Never rows or text. `rows_hash` is NOT sent** (audit only) |
| `tool_error` | `call_id`, `tool`, `error`: `login_refused`\|`unreachable`\|`failed`, the tool server's `_meta.error` (§10; unreadable evidence and transport failures are `failed`). **The data source is NOT sent** (audit only) |
| `answer_chunk` | `content`: the whole answer as the user gets it (one chunk) |
| `verification` | `verdicts`: `[{"handler": <name>, "status": "PASS"\|"WARN"\|"FAIL", "note": <string\|null>}]` |
| `done` | `evidence_ids` (every evidence id of the exchange, cited or not), `grounded` (bool: any evidence), `answer`, `segments`, `figures`, `tables`, `quotes`, `provenance` (shapes in §6.6) |
| `error` | `message`: exactly one of the three fixed sentences below |

Fixed error sentences (`ChatEvent.Error`):

| Constant | `message` | When | audit `reason` |
|---|---|---|---|
| `MODEL_UNAVAILABLE` | `The model is not available right now. Please try again in a moment.` | the model server refused, timed out, unreachable (any HTTP/transport error from the LLM client) | `llm_unavailable` |
| `NO_MODEL_ENDPOINT` | `The airlock's model endpoint is not available` | the airlock's provider id ≠ the main LLM's client's provider (`ollama`, `openai`), or no client | `no_model_endpoint` |
| `COULD_NOT_COMPLETE` | `The assistant could not complete the request` | handlers fail to load, deadline, tool-call budget spent twice, tool listing failed, anything unexpected | `deadline` or `internal` |

### 4.3 JSON answer (`Accept: application/json`)

200, exactly these twelve fields in this order (`ChatAnswer.java`):

| Field | Type | Meaning |
|---|---|---|
| `answer` | string | text the user gets, every figure written in |
| `dont_know` | bool | `Answers.isDontKnow(answer)`: after stripping leading `[\s*_>"'`“‘]+`, blank or starts (case-insensitive) with `i don['’]t know` not followed by an alphanumeric (`libs/config/.../Answers.java`) |
| `segments` | array | as `done` |
| `figures` | array | as `done` |
| `tables` | array | as `done` |
| `quotes` | array | as `done` |
| `provenance` | object | as `done` |
| `evidence` | array | per evidence event, in order: `{"id", "source" (= evidence.tool), "name" (= evidence.entity), "rows" (= row_count), "data_source"}` |
| `tool_errors` | array | per `tool_error` event, in order: `{"call_id", "tool", "error"}` |
| `verification` | object | `{"verified": bool, "verdicts": [{"name": <handler>, "verdict": "PASS"\|"WARN"\|"FAIL"}]}`; `verified` = ≥1 verdict and all `PASS` |
| `revision` | string | the airlock's revision |
| `trace_id` | string\|null | the exchange's root trace id (32 hex), `null` only without tracing |

If no `done` arrived (or it had no provenance), the grounded fields come from `GroundedAnswer.plain(answer)`.
An exchange that ended in `error` is a problem document instead: **503** detail = the sentence for
`MODEL_UNAVAILABLE`/`NO_MODEL_ENDPOINT`, **500** detail `The assistant could not complete the request` otherwise
(`GlobalExceptionHandler.exchangeFailed`).

### 4.4 Errors (RFC 9457, `Content-Type: application/problem+json`)

Body shape: `{"type":"about:blank","title":<reason phrase>,"status":<n>,"detail":<detail>,"instance":<path>}`
(Spring `ProblemDetail`; `type`/`instance` may be omitted, clients read `status` and `detail`).

| Status | detail | When | Java |
|---|---|---|---|
| 400 | `message is blank or too long` | validation | `GlobalExceptionHandler.invalid` |
| 401 | body exactly `{"status":401,"title":"Unauthorized"}`; header `WWW-Authenticate: Bearer resource_metadata="<AISOLATE_PUBLIC_URL>/.well-known/oauth-protected-resource"` | no token, bad signature, expired, wrong issuer, audience ≠ `aisolate` | `SecurityConfig.apiSecurity` |
| 403 | `forbidden` | admission refused (§5); the log line `403 POST <uri>: <space>/<airlock> does not admit user <email> from client <azp>`, or `… does not admit client <azp>` when the token has no e-mail | `GlobalExceptionHandler.forbidden` |
| 404 | `unknown airlock` | id not served by this pod, or space mismatch | `GlobalExceptionHandler.unknownAirlock` |
| 503 | `audit unavailable` | audit buffer full and fail-closed (§7) | `GlobalExceptionHandler.auditUnavailable` |
| 500/503 | the fixed sentence | JSON answer only (§4.3) | `GlobalExceptionHandler.exchangeFailed` |

No 413/429: the server queues nothing in front of the model; a busy model is `MODEL_UNAVAILABLE`.

## 5. Admission (gate 2)

Token validation (gate 1, `SecurityConfig` + `application.properties`): JWT signature via the issuer's JWKS
(discovered from `AISOLATE_ISSUER_URI` + `/.well-known/openid-configuration`), `iss` = `AISOLATE_ISSUER_URI`
exactly, `aud` contains `aisolate`, `exp`/`nbf` checked. Roles are read from the dot path `AISOLATE_ROLES_CLAIM`
(default `realm_access.roles`; a string or list of strings), but **roles play no part in airlock admission**.
Startup fails if the audience is empty (`SecurityConfig.requireAudience`).

Then, per request (`AirlockAdmissionService.admit`, `libs/identity/.../Principal.java`), in this order:

1. `auditLog.accepting()` false → **503** `audit unavailable` (nothing admitted, no refusal line).
2. Find the airlock: `airlockId` served by this pod **and** its `spaceId` equals the path's → else
   **404** `unknown airlock` (no audit line).
3. Principal from claims: `sub` (required, non-blank string; Java throws and answers 500, Python answers 401), `email` (string or
   null), `azp` (string or null), roles.
4. `admissionFailure(users, callers, clients)`:
   - admitted if `email` is null, `azp` is non-null and `clients` (the Airlock's `access.clients`) contains
     `azp` (exact, case-sensitive; `*` is no wildcard); `callers` are not consulted.
   - else `USER` if `email` is null or no entry of `users` equals it **case-insensitively** (`equalsIgnoreCase`).
     `users` = the Space's member e-mails ∪ the Airlock's own users, as the mounted documents list them
     (`Configuration.accessOf`).
   - else `CLIENT` if `azp` is null, or `callers` contains neither `"*"` nor `azp` (exact, case-sensitive).
   - user is checked first.
5. On failure: span tags (§8), one **refusal** audit line (§7, `reason` = `user` | `client`), then **403**
   `forbidden`.

The refusal line's `question` is the request's message; `duration_ms` is from step 2 to the refusal.

## 6. The agent loop

`AgentLoopService.answerUserMessage` → `ChatExchange`. One exchange runs start to finish on one task; no
parallel tool calls.

### 6.1 Steps

1. `started = now`. Model check: the main LLM's (`AISOLATE_LLM`) provider must be one the pod has a client for
   (`ollama`, `openai`) → else `error NO_MODEL_ENDPOINT`, stop.
2. `deadline = now + AISOLATE_EXCHANGE_MAX_DURATION` (default `8m`; ISO-8601 or Spring duration like `8m`, `90s`).
3. Resolve handlers (§6.3); a failure → log `handlers of a/v could not be loaded: <msg>`, `error
   COULD_NOT_COMPLETE`, stop.
4. Log `exchange_start airlock=<space>/<id> revision=<r> tools=[..] data_sources=[..]` and `handlers_resolved
   airlock=<space>/<id> context_prep=[..] memory=[..] verifier=[..]`.
5. `open()` (§6.2): list tools, run context preparators then memory recalls, build the conversation.
6. Ask the model with the offered tools (tool-calling loop, §6.4).
7. **Nudge**: if the airlock offers ≥1 tool, no tool call was attempted, and there is no evidence (not even
   passages) → append the model's reply as assistant and user `Use the tools before answering. Call the relevant tool now; do not answer from memory.`; ask again with tools (once).
8. If the tool-call budget ran out (§6.4) → append user `ANSWER_NOW` + every tool result as the model saw it
   (`"\n\n[" + ev-id + "] " + shown-json` each); ask **without tools**. If that also ends in the tool limit →
   `error COULD_NOT_COMPLETE`.
9. Ground the reply (§6.5–6.6), then `answer()`: emit `answer_chunk`, run verifiers, emit `verification` (if any
   verifiers), emit `done`, run memory `remember` (§6.3).
10. Always (finally): build the audit line (§7), tag the root span, record it.

Failures: deadline (`ExchangeDeadlineExceededException`) → audit reason `deadline` + `error COULD_NOT_COMPLETE`;
model/transport failure → `error MODEL_UNAVAILABLE` (decided by exception *type*, never message); anything
else → log `exchange failed for a/v` + `error COULD_NOT_COMPLETE`. The deadline is checked before each model
request (`requireConversationWithinBudget`) and after each tool call.

### 6.2 Prompt assembly (`ChatExchange.open`)

Messages, in order:

1. **system**: `PLATFORM_PREAMBLE + "\n\n" + <airlock spec.prompt> + "\n\n" + AnswerFormat.INSTRUCTION`.
   Copy both constants verbatim from `ChatExchange.PLATFORM_PREAMBLE` and `AnswerFormat.INSTRUCTION` (the latter
   ends with `AnswerFormat.SCHEMA`, compact JSON, no `$ref`). Put them in the conformance data, not retyped.
2. **user** (only if handlers added anything): `"<context>" + data + "\n</context>"` where `data` =
   - if any `add_context` texts: `"\n\nContext for this question:"` + for each text (stripped, blank ones
     dropped) `"\n\n" + text`;
   - if any passages: `"\n\nDocuments for this question, each under its evidence id:"` + per evidence, per
     passage i (1-based): `"\n\n[" + ev-id + ", passage " + i + "] " + title + ", " + section + "\n" + body`;
   - then every match of `(?i)<\s*/?\s*context\s*>` in `data` replaced by `[context]`.
3. **user**: the question exactly as sent.

No token, sub, e-mail or role goes into any message. No conversation history beyond this exchange (a memory
handler may add context). Model options: temperature `0.1`, `think=false`, `keep_alive=-1m`, the main
LLM: catalog entry `AISOLATE_LLM`'s `_MODEL` at its `_URL` (Ollama `/api/chat`, non-stream; an `openai` one
`<url>/chat/completions` with its key as the bearer, tools as OpenAI functions with call ids, the answer format as a
non-strict `json_schema` `response_format`: [The runtimes](llms-and-data-sources.md#the-runtimes); no default
model); connect timeout 5 s, read timeout `AISOLATE_MODEL_READ_TIMEOUT` (default `90s`); **no retries**.

### 6.3 Handlers (`HandlerChain`, `ChatExchange.prepareContext/verify/remember`)

The airlock document lists handler names per stage (`spec.preLLM.contextPreparator.handlers`,
`spec.preLLM.conversationMemory.handlers`, `spec.postLLM.answerVerifier.handlers`; Java: class simple name;
Python: the class's `__name__`, the manifest's `handlers[].name`). Each listed name must match exactly one handler of the airlock's code
in that stage, else the airlock fails to resolve (`no <stage word> <name> in the code of <s>/<a>` /
`handler <name> is declared twice`) → step 3 error. Unlisted handlers never run. Within a stage: by `order`
ascending, then name (string compare).

| Stage | When | Span stage | Failure |
|---|---|---|---|
| context preparator `prepare(exchange, manager)` | before the model, first | `pre_llm` | logged by exception type only (`handler <n> failed at context_prep with <Type>`), a wrong LLM or data source id with its message (`handler <n> failed at context_prep: <message>`); exchange goes on; what it added stays |
| memory `recall(exchange, manager)` | after all context preparators | `pre_llm` | same, `memory_recall` |
| verifier `verify(answer, evidence, manager) → Verdict` | after grounding, on the grounded answer | `post_llm` | any exception → `WARN` note `verifier failed: <Type>` (a wrong LLM or data source id: `verifier failed: <message>`); `None` → `WARN` `returned no verdict`; no status → `WARN` `returned no status`. The verdict's handler name is always the listed name |
| memory `remember(exchange, answer, manager)` | after `done`, with the verifiers' `Answer` (all their verdicts) | `post_llm` | logged, `memory_remember` |

Verdicts never block or change the answer; `FAIL` only shows in `verification`. Each verifier receives
`Answer(text, verdicts so far, question, grounding_json)` — `question` the user's message as typed, `grounding_json`
the grounded answer as JSON text `{answer, segments, figures, tables, quotes, provenance}` (shapes §6.6; the same
object the `done` frame is built from) — and `exchange.evidence()` (every evidence: `{id, source, content_json}`,
passages as their JSON list, tool results as the tool server's JSON). `remember` receives the same `Answer`, with
every verdict. `add_evidence`/`add_context` after context preparation raise (`IllegalStateException`: `the exchange
accepts evidence and context only during context preparation (prepare and recall)`). `add_evidence(source, passages)`
with none returns `None` and adds nothing; otherwise assigns the next `ev-n` and emits its `evidence` frame
immediately. Log lines: `handler_ran stage=<s> name=<n> ms=<ms>`, `verifier_ran name=<n> status=<S> ms=<ms>`.

Manager per exchange (`ExchangeManagerFactory`): `principal` = `{sub, email, roles}` from the token;
`airlock` = `{space, airlock, revision}`; `llm(id)` → the catalog
entry's handle, `embed(text | texts)` (`/api/embed`; `openai`: `<url>/embeddings`) and `chat(prompt)` (`/api/chat`;
`openai`: `<url>/chat/completions`), unknown id → `LlmException` / `LlmError` `no model named <id> in this
airlock's space or globally`, a 501 on embed / 400 `does not support chat` / an `openai` 401 → the plain messages
of [Error messages](llms-and-data-sources.md#error-messages); a handler failing with
`LlmException`/`LlmError` is logged with its message, any other exception by type only; `data_source(id)` → a DB
connection for one of the airlock's data sources (unknown id → error `<space>/<airlock> has no data source
<id>`, logged with its message like an `LlmError`; tags the current handler span `aisolate.data_source=<id>`; connect timeout 5 s, socket 10 s); `log` =
logger `aisolate.handlers`.

### 6.4 Tools and budget

- `tools/list` once per pod (cached after the first success) only if the airlock's `tools` is non-empty;
  offered = listed tools whose name ∈ `tools`, with the server's name, description, input schema. A listing
  failure fails the exchange (`COULD_NOT_COMPLETE`).
- A tool name the model invents (not offered) is answered with the tool result text `Tool not available: <name>`
  and counts as an attempt (no `tool_call` frame, no audit tool entry, metric label `unknown`).
- **Budget**: `MAX_TOOL_CALLS_PER_EXCHANGE = 3` total tool calls (offered or unknown) per tool-calling loop; the
  4th request ends the loop as "ran out" → step 8. Exchange deadline 8 min. MCP request timeout 20 s.
- A call with the same tool and arguments (any key order) as one that already returned rows in this exchange is not
  run: its tool result is `REPEATED_CALL` (`conformance/cases/prompts/airlock.json`, the tool's name in it), two
  newlines, then that first result as the model saw it. No `call-n`, no frame, no audit tool entry; it counts
  towards the budget.
- Per call (`ChatExchange.runTool`): `call-n` = next attempt number; arguments must parse as a JSON object
  (empty/blank → `{}`), else tool result `Error: arguments must be a JSON object` (no frame). Emit `tool_call`,
  then call the tool server under span `tool <name>`. On success: next `ev-n`; emit `evidence`; number every
  row with a handle `r<k>`, **k counted across the whole exchange** (second result continues at r4…); the model
  receives exactly `{"evidence_id": "ev-n", "row_count": n, "rows": [{"row": "r1", ...row}, ...]}` (compact JSON,
  numbers as exact decimals, never floats). On tool error: result `Error: <fixed message>` (§10); unreadable
  JSON: `Error: the tool server returned an unreadable result`; either way emit `tool_error`. Log `tool_called tool=<t> rows=<n>
  evidence=<ev> ms=<ms>`.
- **Evidence ids**: `ev-n` = 1 + number of evidence frames so far, shared by passages and tool results, in emit
  order.
- **Deny is silent** (`mustDeny`): if there is ≥1 evidence and every evidence has `row_count == 0`, or ≥1 tool
  call failed and there is no evidence → the answer is exactly `I don't know` (plain), whatever the model wrote;
  when every failed call's `error` is `login_refused` or `unreachable`, exactly `I couldn't reach the data to
  answer this.` instead (`COULD_NOT_REACH_DATA` in `cases/prompts/airlock.json`).

### 6.5 The structured final turn (`ChatExchange.ground`, `AnswerFormat`)

1. Parse the model's last reply as the format (`AnswerFormat.parse`): strip, remove a leading
   ```` ```json ````/```` ``` ```` fence and a trailing ```` ``` ````; must be a JSON object with string `text`;
   `figures`/`quotes` absent or null = `[]`, present non-array → error; each figure an object with `id`;
   `dont_know` default false. Parse errors (for the retry): `it is not JSON`, `it is not a JSON object`,
   `text is missing`, `figures is not a list`, `quotes is not a list`, `every figure needs an id`.
2. Parsed → ground it. Not parsed → `raw = ground_raw(reply)`; if `raw` has no figures **and** no tool result has
   rows → return `raw` (greetings, documents-only answers).
3. Else append assistant(reply) and user `AnswerFormat.ANSWER_IN_FORMAT` + every tool result as shown
   (`"\n\n[ev-n] " + shown`); ask **without tools**, with Ollama `format` = `AnswerFormat.SCHEMA` (structured
   output). Parsed → ground.
4. Else append assistant(that) and user `AnswerFormat.RETRY % <parse error or "unknown">`
   (`That answer is not in the answer format (%s). Write it again as one JSON object in the answer format.`);
   ask once more in format. Parsed → ground; else log `answer_format airlock=<a>/<v> not followed twice` and
   return `raw` (step 2's).

### 6.6 Grounding (`Grounding.java`, `GroundedAnswer.java`; spec: `docs/verification.md`)

Port `Grounding.java` method by method; the golden set (`conformance/cases/grounding/`) is the contract. Key rules:

- `dont_know: true` → plain `I don't know`. Placeholders: `\{([A-Za-z][A-Za-z0-9_-]*)}` in `text`. The first ref
  per id wins; a quote id that is also a figure id is ignored.
- Each placeholder: a table ref → table; else figure (a missing ref → mismatch `the answer places {id} but gives
  no reference for it`; both/neither of cell & computed → mismatch). Then the words beside it are checked (sign,
  scale, currency/unit before/after: `besideFigure`) and the figure's own unit next to it dropped.
- Cell resolution: evidence must be a tool result in this exchange (`ev-1 is a document, not a tool result: quote
  it`), handle must belong to it, column masked (in `labels.masked` or value `****`) → mismatch `column <c> of
  <ev> is masked: it cannot be cited`; column ∉ columns → `<ev> (<entity>) has no column <c>`.
- Display: null → `—`; number → rounded to `labels.decimals[col]` (HALF_UP, all shown), else an `avg` group
  value to 2 places trailing zeros stripped, else as held; zero fractions dropped (`78000.00` → `78,000`,
  `1234.50` → `1,234.50`); thousands grouping except for the row-key/subject/period columns; unit appended
  (`%` without space, others with a space) from `labels.units[col]` or the row's `labels.unit_columns[col]`.
  `value` = plain decimal without trailing zeros (exact; computations in 34-digit decimal, `DECIMAL128`; a
  division to 10 places in `value`).
- Unresolved figure: `display` `…`, no value, status `mismatch`, `reason`.
- Typed facts (`raw`, the free text, and the text of a quote that is not `quoted`; a verbatim quote is never
  scanned): each value of a column named in a tool result's `labels.facts` that is a string of ≥ 3 characters after
  `strip` has the key `strip`, ASCII whitespace runs → one space, lower-case; the first holder wins (result, then
  fact column, then row order), `<ev> <r> <col>`. One pattern over the keys, longest first (stable sort), each key's
  words quoted and joined by `\s+`: `FACT_BEFORE(?:k1|k2…)FACT_AFTER`, case-insensitive with Unicode case folding,
  where `FACT_BEFORE = (?<![\p{L}\p{N}])(?<!\p{N}[.,:/-])` and `FACT_AFTER = (?![\p{L}\p{N}])(?![.,:/-]\p{N})`:
  never inside a word, nor inside a number token (the `5 mg` of `2.5 mg`). A match whose key is a whole phrase of the
  question's key (the same two lookarounds around it: `insulinoma` does not hold `insulin`) is skipped and stays in
  the text; any other is an `unsourced` figure `u<n>`, `display` the words as typed, no value, `reason` `typed in the
  text, not referenced; <ev> <r> <col> holds the same value`.
- Typed digits (`raw` and the free text), over the text between the fact matches, so the `5 mg` of a dose is one fact,
  not the number `5`, and the ids `u1, u2…` run in text order across both: every token matching `NUMBER` (copy the
  regex) is an `unsourced` figure unless the question has the same token (comma-insensitive, lower-case), it is
  `ev-\d+`, or it is a list marker (only digits, alone at the start of its line, followed by `.` or `)` and
  whitespace). Reason `typed in the text, not referenced` + `; <ev> <r> <col> holds the same value` when a
  non-masked cell equals it.
- Statuses: figures `sourced` | `computed` | `unsourced` | `mismatch`; tables `sourced` | `mismatch`; quotes
  `quoted` (passage contains the text, whitespace-normalised) | `mismatch` (`these words are not in <ev>, passage
  <i>`; its numbers and fact values are then unsourced). `provenance.status`: `has_mismatch` > `has_unsourced` > `all_sourced`;
  `no_figures` when no figure or table; a table counts once; quotes don't count.
- Output JSON (null fields omitted, `@JsonInclude(NON_NULL)`): Segment `{text}|{figure}|{table}`; Figure `{id,
  display, value, status, source, computed, reason}`; Source `{evidence, entity, column, row_key, subject,
  period, unit, value, display}`; Computed `{op, of[], n, by{evidence, entity, column}, rows[], row, call{tool,
  args}}`; Table `{id, status, display, evidence, entity, call, columns, rows[[Cell]], rank{op, by}, reason}`;
  Cell `{display, value, status, source}`; Quote `{id, status, evidence, index, title, section, text, reason}`;
  Provenance `{status, sourced, computed, unsourced, mismatch}` (all five always present). `call.args` =
  `ChatExchange.applied`: only arguments the tool's input schema declares, non-empty, and not a `limit` larger
  than the rows returned.

## 7. Audit trail

### 7.1 The line (`domain/chat/audit/AuditEvent.java`, `ExchangeAudit.java`; field doc: `docs/security.md#audit-trail`)

One JSON object, snake_case, keys in this order, **all keys always present** (null as `null`):

| Field | Type | exchange | refusal |
|---|---|---|---|
| `ts` | string ISO-8601 UTC, ms precision, `Z` (`2026-10-16T20:58:38.434Z`) | exchange start | admission start |
| `event` | `"exchange"`\|`"refusal"` | | |
| `trace_id` | string\|null | root trace id | same |
| `space`, `airlock`, `revision` | string | | |
| `user` | string\|null | token `email`; null for a machine client | same |
| `client` | string\|null | token `azp`; a machine client's id | same |
| `question` | string | message as sent | same |
| `answer` | string\|null | the `answer_chunk` content; null if none | null |
| `outcome` | `answered`\|`dont_know`\|`refused`\|`error` | `error` if an error event or no answer; else `dont_know` by `Answers.isDontKnow`; else `answered` | `refused` |
| `reason` | string\|null | for `error`: `llm_unavailable`\|`no_model_endpoint`\|`deadline`\|`internal` (first error wins, `deadline` overrides) | `user`\|`client` |
| `tools` | array | per `tool_call` in order: `{name, args, data_source, rows, rule_version, sql_hash, rows_hash, error}`; until its evidence arrives all but `name` are null (a failed call keeps `args: null`; its `tool_error` sets `error` and `data_source`) | `[]` |
| `evidence` | string[] | evidence ids **cited**: ids of sourced/computed figures' `source`, `computed.of[]`, `computed.by`, sourced tables, quoted quotes, plus any `\bev-\d+\b` in the answer text; ordered as emitted | `[]` |
| `verifiers` | array | `[{name, verdict}]` | `[]` |
| `figures` | object\|null | `{sourced, computed, unsourced, mismatch}` from `done.provenance`; null on error | null |
| `grounding` | object\|null | `{segments, figures, tables, quotes}` from `done`, with every key `value`, `subject`, `period`, `unit` removed **recursively** (`AuditLog.stripRowValues`); null on error | null |
| `model` | object\|null | `{provider, name, calls, input_tokens, output_tokens}` of the **main** LLM (a handler's `llm(id)` calls are not in it); `calls` counts every model request incl. failed; tokens summed from Ollama `prompt_eval_count`/`eval_count`, null if never reported | null |
| `duration_ms` | int | start → after the last event | admission time |

`rows_hash` (`ChatExchange.rowsHash`): sha256 hex of compact JSON of the rows, each row's keys sorted, every
number replaced by its plain-decimal **string** without trailing zeros (`0` for zero), UTF-8, non-ASCII
unescaped (the `rows_hash.py` in `docs/verification.md` is the reference). Exactly one line per exchange (always,
in `finally`) and one per 403 refusal; none for 400/401/404/503.

### 7.2 Delivery (`AuditSink.java`, `AuditLog.java`, `ChatConfig.auditSink`)

| Env | Default | Meaning |
|---|---|---|
| `AISOLATE_AUDIT_URL` | empty | backend ingest, `http://<backend>:8085/v1/audit/events`, rendered by the controller; empty = no sink (stdout only, always printed) |
| `AISOLATE_AUDIT_TOKEN_FILE` | — | file with the airlock's ingest token (HMAC-SHA256 of `<space>/<airlock>`, computed by the controller; the pod only reads it). **Re-read on every send**, stripped |
| `AISOLATE_AUDIT_BUFFER_DIR` | `/var/lib/aisolate/audit` | emptyDir |
| (buffer max) | 64 MB | `aisolate.audit.buffer-max`; no env today |
| `AISOLATE_AUDIT_FAIL_CLOSED` | `true` | |
| `AISOLATE_AUDIT_STDOUT` | `true` | |

- `offer(line)` (serialised by one lock): if not fail-closed and buffered ≥ max → drop (counter
  `aisolate.audit.dropped` + error log). Else write `<seq>.tmp`, atomic rename to `%019d.json` (seq zero-padded to
  19 digits), add its bytes. Disk error → dropped. No fsync.
- Start: create the dir; delete `*.tmp`; next seq = max existing + 1; buffered = sum of sizes; start one sender.
- Sender: oldest `*.json` by name; `POST <url>` body = the file bytes, `Authorization: Bearer <token>`,
  `Content-Type: application/json`, connect 5 s, request 10 s. 200/201 → delete, reset backoff. 400 → log and
  delete (never retried). Anything else / IO error → sleep backoff (0.5 s doubling to max 30 s), retry the same
  file. Strict order.
- **Fail closed**: `accepting()` = no sink, or not fail-closed, or buffered < max. Checked at admission only (§5
  step 1) → 503 `audit unavailable`. An admitted exchange's line is always offered (even over the bound).
- Shutdown: drain for up to 10 s, then log `audit trail: <n> bytes of lines not handed over at shutdown`.
- **stdout**: when `stdout` or no sink, print the line alone (`<json>\n`, nothing before it, UTF-8) on stdout; no
  other stdout/log line may start with `{"ts"`. Java: logger `aisolate.audit`, pattern `%msg%n`, non-additive.
  The pod's own log lines must never contain a newline-injected model string (Java silences
  `org.springframework.ai.model.tool` below ERROR for that reason).
- `record` never raises; metrics are counted from the line (§9).

## 8. Tracing (`ExchangeTracing.java`, `OwnTraceFilter.java`, `libs/identity/.../TracingConfig.java`, `docs/observability.md`)

- Export: OTLP/HTTP protobuf to `MANAGEMENT_OTLP_TRACING_ENDPOINT` (base URL; strip trailing `/`, append
  `/v1/traces` unless already there). Unset/blank → spans still created (trace id exists), not exported.
  Sampling 1.0. Propagation W3C `traceparent`/`tracestate`.
- **Inbound trace headers are ignored**: `traceparent`, `tracestate`, `baggage`, `b3`, `x-b3-traceid`,
  `x-b3-spanid`, `x-b3-parentspanid`, `x-b3-sampled`, `x-b3-flags` (case-insensitive). Every request starts a new
  trace.
- Outbound: `traceparent` (+`tracestate`) of the current `tool <name>` span on every MCP HTTP request.

Spans (name — attributes; every AIsolate span carries `aisolate.space`, `aisolate.airlock`,
`aisolate.revision` once known):

| Span name | Parent | Attributes |
|---|---|---|
| HTTP server span (`http post /v1/spaces/{spaceId}/airlocks/{airlockId}/chat`) | root | route template only, never the concrete path's query |
| `exchange <space>/<airlock>` | HTTP | `aisolate.space`, `aisolate.airlock` (from the path), then `aisolate.revision`, `aisolate.client` (azp), `aisolate.outcome` (`answered`\|`dont_know`\|`refused`\|`error`), `aisolate.figures.sourced`, `.computed`, `.unsourced`, `.mismatch` (ints, when answered) |
| `admission` | exchange | `aisolate.admitted` `true`/`false`, `aisolate.reason` `user`/`client` on refusal; status ERROR on refusal (exchange too) |
| `<handler description or name>` | exchange | `aisolate.handler` (name), `aisolate.stage` `pre_llm`/`post_llm`, `aisolate.passages` (int, pre_llm), `aisolate.data_source` (if it opened one), `aisolate.verdict` (verifiers) |
| model call spans (`chat <model>`, Spring AI's `spring_ai chat_client`/`tool_calling`) | exchange | GenAI semconv: `gen_ai.operation.name`, `gen_ai.system`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`; `aisolate.llm` (the LLM id). A handler's `llm(id).chat` makes one too. Python: one span `chat <model>` per model request (exact Spring AI nesting not required) |
| `embedding` | handler | `gen_ai.usage.input_tokens`, `gen_ai.request.model`, `aisolate.llm` |
| `tool <name>` | exchange (or model loop span) | `aisolate.tool`, `aisolate.rows` (int) |
| HTTP client `http post` | tool / chat | — |

Errors: status ERROR + `error.type` = exception class name; **no exception message, no event**.

**Never in a span** (nor in a metric label): question, answer, prompt, completion, passage, row, figure value,
SQL, token, e-mail, user, role, exception message. No security-framework spans. LLM content capture off.

The `trace_id` of the audit line and of the JSON answer = the `exchange` span's trace id (32 lower-case hex).

## 9. Metrics (`AuditLog.count`, `ExchangeTracing.MODEL_CALLS_BY_AIRLOCK`; served on `:9464/actuator/prometheus`)

Counted from the audit line, so they agree with it. Labels only from closed sets.

| Series (Prometheus) | Type | Labels | When |
|---|---|---|---|
| `aisolate_exchanges_total` | counter | `airlock`, `outcome` | every line (refusals too) |
| `aisolate_refusals_total` | counter | `airlock`, `reason` | refusal lines |
| `aisolate_tool_calls_total` | counter | `airlock`, `tool` | per `tools[]` entry of an exchange line |
| `aisolate_exchange_duration_seconds` (`_bucket`, `_count`, `_sum`) | histogram | `airlock` | exchange lines, `duration_ms/1000`. Buckets not part of the contract (Micrometer's defaults, 1 ms–30 s+); Python: `0.5,1,2,5,10,20,30,60,120,240,480,+Inf` |
| `aisolate_audit_buffered_bytes` | gauge | — | buffer fill |
| `aisolate_audit_dropped_total` | counter | — | dropped lines |
| `gen_ai_client_token_usage_total` | counter | `airlock`, `gen_ai_token_type` (`input`\|`output`\|`total`), `gen_ai_operation_name`, `gen_ai_system`, `gen_ai_request_model` | per model call with usage; `airlock` `none` outside an exchange |

The example dashboard reads `aisolate_exchanges_total`, `aisolate_refusals_total`, `aisolate_tool_calls_total`,
`aisolate_exchange_duration_seconds_bucket` and `gen_ai_client_token_usage_total{gen_ai_token_type!="total"}`. JVM/
Spring series (`jvm_*`, `http_server_requests_*`, `spring_ai_tool_seconds`) are not part of the contract.

## 10. Airlock → tool server (MCP client; `infrastructure/mcp/McpSyncToolClient.java`, `McpClientConfig.java`)

- Endpoint: `AISOLATE_TOOL_SERVER_URL` (default `http://localhost:8081/mcp`); a bare origin means path `/mcp`.
  MCP **streamable HTTP**, client info `{"name":"aisolate-airlock","version":"0.1.0"}`, request timeout **20 s**.
- **Stateless**: no session reuse; the `initialize` handshake runs inside the first call and carries that caller's
  bearer. (Python: a fresh `ClientSession` per call, or raw JSON-RPC `POST /mcp` with `Accept: application/json,
  text/event-stream`, is fine.)
- **Bearer** = the asking user's own token, per request (`Authorization: Bearer <user JWT>`). No service
  credential exists; a call without a current user must fail before sending. Never share a token across
  concurrent exchanges.
- **`_meta`** on every `tools/list` and `tools/call`: `{"airlock": <id>, "revision": <revision>}`, always from the admitted airlock, never from model output.
- `tools/list`: first page only; cached per pod after the first success. Each tool → name, description, input
  schema (JSON).
- `tools/call` `{name, arguments, _meta}`: result text = the first `TextContent`'s text (`""` if none). If
  `isError` → tool error with that text as message (shown to the model as `Error: <text>`; the tool server's text
  is a fixed sentence), its category `_meta.error` (`login_refused`\|`unreachable`\|`failed`; anything else or
  missing → `failed`) and `_meta.data_source` (a string, else none). The text otherwise is the evidence JSON (`ToolEvidence.java`):
  `{entity, row_count, columns[], rows[{col: value}], rule_version, rules[], sql_hash, data_source, labels{row_key,
  subject, period, units{col: unit}, unit_columns{col: col}, decimals{col: int}, masked[], function, facts[]}}` —
  parse numbers as exact decimals; unknown fields ignored; missing `columns`/`rows`/`facts` = `[]`; missing labels
  = none.
- Transport failures become fixed messages, never the SDK's text (it quotes headers and bodies): HTTP 401/403 →
  `the tool server rejected the request`; anything else → `the tool server could not be reached`. Log only the
  exception type: `call to the tool server failed with <Type>`.

## 11. How a Python runtime loads project code

The Java pods load the jar (§3b). A Python pod loads the zip artifact, as [`python-artifact.md`](python-artifact.md)
§"Delivery and loading" says: fetched as §1.4.1 says, digest pin (`AISOLATE_CODE_DIGEST`, the same `sha256:<hex>` over
the file bytes as `JarDigests`), safe extraction to `/tmp/aisolate/code`, `vendor/` and `src/` appended to the end of `sys.path`,
`aisolate.build.discover(package)` then `aisolate.build.check(catalog, manifest)`; any problem fails the start
(exit 1, one log line naming it; readiness never goes UP). Then, as in Java:

| Java | Python |
|---|---|
| `JarScan` finds every concrete `ToolPlugin` / `ContextPreparator` / `ConversationMemory` / `AnswerVerifier` | `discover` finds every concrete `aisolate.ToolPlugin` subclass and every class marked `@context_preparator` / `@memory` / `@verifier` whose `__module__` is under the package |
| handler name = simple class name; `order()`; `@Description` | `__name__`; `order=`; `description=` |
| `HandlerChain.resolve`: a listed handler the code lacks fails the start | the same, by name and stage |
| `CatalogCheck.check` (§3.8) | the same checks, in `discover` (tool server and airlock) and in the backend from the manifest |
| `RuleSetFiles` reads `aisolate/rules/*.json` from the jar | the tool server reads `rules/*.json` from the extracted zip, same checks |
| a `Tool.Handler` returning a non-`Result` → generic error, logged | a `@tool` method returning a non-`Result` → the same generic error |
| `IllegalArgumentException` from a tool → its message to the model | `ValueError` from a tool → its message to the model |
| a context preparator's `RuntimeException` logged, exchange goes on | any `Exception` (not `BaseException`) logged, exchange goes on |
| a verifier's `RuntimeException` → `WARN` verdict | any `Exception` → `WARN` verdict |

Handlers and tools are synchronous in both SDKs. The Python runtimes are asyncio (Starlette/uvicorn) and call
project code with `anyio.to_thread.run_sync`, so a slow handler never blocks other exchanges; the exchange
deadline (§6.4) still applies.

`aisolate.AIsolateManager.data_source(id)` (the Java `dataSource(id)`) yields a pg8000 DB-API connection from the
pod's pool for that id (§1.2 data-source env; the URL is a JDBC URL: strip `jdbc:`, parse
`postgresql://host:port/db?params`), returned to the pool on exit, rolled back unless committed.
`llm(id)` returns the catalog entry's handle (`embed`, `chat`); an unknown id, a model that cannot embed or chat
raise `aisolate.LlmError` (a `ValueError`; Java: `LlmException`, an `IllegalArgumentException`) with the plain
messages of [Error messages](llms-and-data-sources.md#error-messages).

## 12. Python dependencies

Licenses: Apache-2.0, MIT (incl. MIT-0), BSD, PSF, ISC only; nothing GPL/LGPL/AGPL/MPL. Python **3.13**; locking
with **uv** (`uv.lock` per Python module, committed). Pinned versions (the runtime set; a project's vendored
packages may not conflict with it):

| Package | Version | License | Used by | For |
|---|---|---|---|---|
| starlette | 1.9.0 | BSD-3-Clause | both | HTTP app (8080/8081 and the 9464 management app) |
| uvicorn | 0.54.0 | BSD-3-Clause | both | ASGI server |
| h11 | 0.16.0 | MIT | both | (uvicorn, httpcore2) |
| sse-starlette | 3.5.1 | BSD-3-Clause | both | the chat SSE stream (also an mcp requirement) |
| mcp | 2.3.0 | MIT | both | MCP streamable HTTP: client in the airlock, stateless server in the tool server |
| mcp-types | 2.3.0 | MIT | both | (mcp) |
| httpx2 | 2.16.0 | BSD-3-Clause | both | HTTP client: Ollama, OpenAI-compatible servers, audit ingest, JWKS, MCP (mcp's own client) |
| httpcore2 | 2.16.0 | BSD-3-Clause | both | (httpx2) |
| truststore | 0.10.5 | MIT | both | (httpx2: system CA store; no certifi) |
| idna | 3.20 | BSD-3-Clause | both | (httpx2) |
| anyio | 4.15.1 | MIT | both | threads for project code, timeouts |
| pydantic | 2.14.1 | MIT | both | (mcp) |
| pydantic-core | 2.50.1 | MIT | both | (pydantic) |
| typing-inspection | 0.4.4 | MIT | both | (mcp) |
| typing-extensions | 4.16.0 | PSF-2.0 | both | (several) |
| jsonschema | 4.26.0 | MIT | both | (mcp); tool input schemas |
| referencing | 0.37.0 | MIT | both | (jsonschema) |
| jsonschema-specifications | 2025.9.1 | MIT | both | (jsonschema) |
| rpds-py | 2026.9.1 | MIT | both | (referencing) |
| attrs | 26.1.0 | MIT | both | (referencing) |
| python-multipart | 0.0.32 | Apache-2.0 | both | (mcp) |
| PyJWT[crypto] | 2.15.1 | MIT | both | token validation against the issuer's JWKS |
| cryptography | 50.0.2 | Apache-2.0 OR BSD-3-Clause | both | (PyJWT RS256/ES256) |
| cffi | 2.1.1 | MIT-0 | both | (cryptography) |
| pycparser | 3.12 | BSD-3-Clause | both | (cffi) |
| **pg8000** | 1.31.5 | BSD-3-Clause | both | **the PostgreSQL driver** (pure Python, DB-API 2.0). Not psycopg (LGPL), not asyncpg (not DB-API, which the SDK promises handlers) |
| scramp | 1.4.18 | MIT-0 | both | (pg8000: SCRAM auth) |
| asn1crypto | 1.5.1 | MIT | both | (scramp) |
| python-dateutil | 3.0.0 | Apache-2.0 OR BSD-3-Clause | both | (pg8000) |
| six | 1.17.0 | MIT | both | (python-dateutil) |
| tzdata | 2026.5 | Apache-2.0 | both | (python-dateutil) |
| opentelemetry-api | 1.45.1 | Apache-2.0 | both | spans |
| opentelemetry-sdk | 1.45.1 | Apache-2.0 | both | tracer provider, batch processor |
| opentelemetry-semantic-conventions | 0.66b1 | Apache-2.0 | both | (sdk) |
| opentelemetry-exporter-otlp-proto-common | 1.45.1 | Apache-2.0 | both | `encode_spans` for OTLP/HTTP protobuf |
| opentelemetry-proto | 1.45.1 | Apache-2.0 | both | (exporter-common) |
| protobuf | 7.36.2 | BSD-3-Clause | both | (opentelemetry-proto) |
| prometheus-client | 0.26.0 | Apache-2.0 AND BSD-2-Clause | both | `/actuator/prometheus` on 9464 |
| PyYAML | 6.0.3 | MIT | both | the mounted documents |
| aisolate (SDK) | 0.1.0 | AGPL-3.0-only | both, projects | `libs/sdk-python` (path dependency) |
| pytest | 9.1.1 | MIT | dev only | tests |
| hatchling | 1.32.4 | MIT | build only | wheel build backend |

Deliberately **not** used: `psycopg` (LGPL-3.0); `httpx` 0.28 and `requests` (both pull `certifi`, MPL-2.0);
`opentelemetry-exporter-otlp-proto-http` (pulls `requests`). The OTLP export is a ~20-line `SpanExporter` that
posts `encode_spans(spans).SerializeToString()` with `Content-Type: application/x-protobuf` to
`MANAGEMENT_OTLP_TRACING_ENDPOINT` + `/v1/traces` (§8) with httpx2 (the Java pods use OTLP/HTTP on the same endpoint, port 4318; the
gRPC exporter would need port 4317, which the egress policy does not open). Before adding any package, check its
license and its whole dependency tree (`uv tree`) against the list above. After changing a pin, regenerate
`libs/sdk-python/src/aisolate/runtime-constraints.txt` (the command is in its header): a project's build resolves
its own dependencies against it (python-artifact.md), and the SDK's tests fail while it differs from the locks.

Images (`apps/airlock-python/Dockerfile`, `apps/tool-server-python/Dockerfile`): a `python:3.13-slim` build
stage pinned by digest runs `uv sync --frozen --no-dev` into `/app/.venv`; the final stage is distroless Python
3.13 (`gcr.io/distroless/python3-debian13`, pinned by digest, no shell, no package manager) with the venv's
site-packages on `PYTHONPATH`, numeric non-root `USER 10001:10001`, `EXPOSE 8080`/`8081` and `9464`, no write
outside `/tmp` (read-only root filesystem, as the Java pods), `PYTHONDONTWRITEBYTECODE=1`,
`ENTRYPOINT ["/usr/bin/python3", "-m", "aisolate_airlock"]` / `aisolate_tool_server`.

## 13. Known divergences to settle

| What | Java today | Contract | Owner |
|---|---|---|---|
| a valid token without `sub` | settled: 401, refused while the token is validated (`SecurityConfig.subjectRequired`, also the tool server's); conformance `admission/token.json` `no-subject` | **401** in both | — |
| egress proxy env | settled: Python pods also get `HTTPS_PROXY`, `NO_PROXY` (§1.6) | as Java, in the Python process's terms | — |

## 14. Details easy to get wrong

Found while writing the conformance cases (`conformance/README.md` pins each):

- SQL text byte-identical (it is hashed onto every audit line); aggregates have no `ORDER BY`.
- Order inside a tool call: SDK argument shaping → rules → `ToolArgs` → SQL. An unknown `op` is a tool error even
  for a denied user; a denied user's filter on a hidden column is the silent empty result.
- One matched rule whose row filter needs an attribute the user lacks denies the whole query, even beside `true`.
- Text values are Java's `String.valueOf` (`true`, not `True`); `limit` truncated then clamped 1..100, a string refused.
- Callers case-sensitive, users case-insensitive and untrimmed; `*` only for callers; no `azp` → `client`.
  Machine clients case-sensitive and exact; only a token without `email` is matched against them, and a miss
  falls through to the users (→ `user`).
- Audit `ts` prints no fraction when the millisecond is 0 (`Instant.toString`); all 20 keys, fixed order.
  `rows_hash` escapes control characters as upper-case `\u00XX` (Jackson), unlike `json.dumps`.
- JWT `exp`/`nbf` with 60 s leeway (PyJWT's default is 0).
- Error messages print enum names upper case: `AVG needs a numeric column…`, `function MIN is not allowed here…`.
- The prompts and the three error sentences are read from `conformance/cases/prompts/airlock.json`, never retyped.
