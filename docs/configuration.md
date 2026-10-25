# Configuration

In a cluster the controller sets each pod's variables from the airlock's documents, the Secrets and the
installation settings the chart renders; nothing is typed per pod. The chart's values are in the
[chart README](https://github.com/reportix/aisolate-charts/blob/main/chart/README.md). The defaults below are
those of the compose stack a developer runs on a laptop.

Every app answers actuator (health for the kubelet, `/actuator/prometheus` for a scraper) on the management port
9464, `MANAGEMENT_SERVER_PORT`, which no Service exposes; the backend also answers `/readyz` on its API port
([Observability](observability.md#metrics)).

## Backend

| Variable | Default | Meaning |
|---|---|---|
| `AISOLATE_DB_URL`, `AISOLATE_DB_USERNAME`, `AISOLATE_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/aisolate`, `aisolate`, required | The backend's database, owner role; Flyway runs here |
| `AISOLATE_ISSUER_URI` | `http://localhost:8180/realms/aisolate` | The operators' identity provider (demo realm `aisolate`: the AIsolate UI and the CLI sign in there); the chart sets it from `platform.identity.operators.issuerUri`, else `platform.identity.issuerUri`. The audience `aisolate` is fixed |
| `AISOLATE_ROLES_CLAIM` | `realm_access.roles` | Dot path of the roles claim in the tokens: the AIsolate UI roles here, the roles the rules and handlers see in the pods; the chart sets the backend's from `platform.identity.operators.rolesClaim`, else `platform.identity.rolesClaim`, and the controller sets the pods' from `platform.identity.rolesClaim` |
| `AISOLATE_PUBLIC_URL` | `http://localhost:8080` | Builds endpoint links and the 401 challenge; in the cluster the release host |
| `AISOLATE_CORS_ALLOWED_ORIGINS` | `*` | Browser origins (the AIsolate UI's): a comma-separated list of origin patterns, unset meaning the default `*`; the token decides, not the origin |
| `AISOLATE_CLUSTER_NAMESPACE` | empty | The namespace the backend applies the resources to and creates the Secrets in. Empty: no cluster, a deploy ends in the database and a data source that brings credentials is refused |
| `AISOLATE_CLUSTER_RESYNC` | `PT5M` | How often the backend applies every space again, so a save the cluster did not take (an API server away) heals |
| `AISOLATE_AUDIT_INGEST_KEY` | empty | The install's key every airlock's audit ingest token is derived from (HMAC-SHA256 of `<space>/<airlock>`), the chart's Secret `audit-ingest`, shared with the controller alone. Empty: the ingest takes no line ([Audit](audit.md)) |
| `AISOLATE_CREDENTIALS_KEY` | empty | The key the backend seals model API keys and data source logins with in its `credentials` table: 32 or more characters, from which the AES-256 key is derived. The chart's Secret `credentials-key` (64 generated characters, kept on upgrades; `platform.credentials.existingSecret` names yours), read by the backend pod alone. Required with `AISOLATE_CLUSTER_NAMESPACE`; an empty key then, or any key under 32 characters, stops the backend at start. Lose it and every stored credential reads as 409 `stored under another key` ([Security](security.md#credentials)) |
| `AISOLATE_CREDENTIALS_REVEAL_CLIENTS` | `aisolate-ui` | The OIDC clients (`azp`) whose tokens may reveal a credential, comma-separated; any other client, the CLI included, is 403 `not from the AIsolate UI` |
| `AISOLATE_CREDENTIALS_REVEAL_MAX_AGE` | `PT5M` | The oldest login, by the token's `auth_time`, that may reveal a credential; an older one is 403 `sign in again to reveal` |
| `AISOLATE_AUDIT_INGEST_PORT` | `8085` | The pods' port: the airlock pods hand their audit lines over on it, both pods fetch their airlock's code there; it answers `POST /v1/audit/events` and `GET /v1/code/…` alone, and the API port never answers those |
| `AISOLATE_MAX_ARTIFACT_SIZE` | empty | The largest artifact a deploy may bring, as Spring reads a size (`500MB`, `2GB`); larger is refused before anything is stored, `jar: larger than <limit>, the most this install takes`. Empty: no limit (the chart's `platform.backend.maxArtifactSize`) |
| `AISOLATE_AUDIT_RETENTION_MONTHS` | `84` | Whole months of the audit trail kept before the current one; older ones are dropped, at start and every six hours (`AISOLATE_AUDIT_RETENTION_INTERVAL`, `PT6H`) |
| `AISOLATE_AUDIT_ENTITY` | empty | The organisation the audit export's XBRL report is of ([Audit](audit.md#export)): its LEI, or an identifier scheme and the identifier (`<scheme>:<id>`). Empty: the install itself, `<AISOLATE_PUBLIC_URL>:aisolate`. A value that is neither stops the backend at start |
| `AISOLATE_METRICS_QUERY_URL` | empty | Prometheus's HTTP API in the cluster, for Monitoring's charts (`http://prometheus.monitoring.svc:9090`), or any store that answers `/api/v1/query` and `/api/v1/query_range` alike. The backend asks it fixed queries only, scoped by the series' `space` label; each call waits at most 10 s. Empty: no Metrics tab, `/v1/metrics` is 404 (the chart's `platform.metrics.queryUrl`) |
| `AISOLATE_TRACING_QUERY_URL` | empty | The trace store's query API in the cluster, for Monitoring's trace page: Jaeger's (`http://jaeger.monitoring.svc:16686`) or any store that answers `GET /api/traces/{id}` as Jaeger's does. The backend reads it, never the browser; each call waits at most 5 s. Empty: no Monitoring, `/v1/traces` is 404 (the chart's `platform.tracing.queryUrl`) |

A deploy's built project, a jar or a Python project's zip, is streamed to the backend's `/tmp`, read there, and
stored in the database in 1 MiB chunks; it is never sent to the cluster: the airlock's pods fetch it from the
ingest port (`GET /v1/code/<space>/<airlock>/<digest>`, with the airlock's code token), streamed from
those chunks. A Python zip above 2 GiB is refused (the manifest reader takes no Zip64).

## Airlock pod

The identity variables above (`AISOLATE_ISSUER_URI`, `AISOLATE_ROLES_CLAIM`, `AISOLATE_PUBLIC_URL`,
`AISOLATE_CORS_ALLOWED_ORIGINS`) apply too, except that the pods' `AISOLATE_ISSUER_URI` and `AISOLATE_ROLES_CLAIM` are the application users' provider (`platform.identity`, demo realm `acme`), not the backend's operators'; the controller sets them.

| Variable | Default | Meaning |
|---|---|---|
| `AISOLATE_CONFIG_DIR`, `AISOLATE_CONFIG_REVISION` | empty | The directory of the airlock's documents (the mount of its `<space>-<airlock>-config` ConfigMap: the `Global`, its `Space` and itself) and the revision the controller computed for them; an empty revision is the hash of the files as read |
| `AISOLATE_CONFIG_DISCOVERY_DIR` | empty | The `aisolate-discovery` mount, what discovery lists of every valid space, re-read when the kubelet swaps the files; set without `AISOLATE_CONFIG_DIR`, the pod is a discovery pod, which serves no airlock and answers every chat path 404; empty: discovery lists the pod's own documents |
| `AISOLATE_AIRLOCK` | empty | The one airlock this pod serves; every other id is 404 at the chat endpoint; a name the documents do not define refuses the start; empty serves every airlock (tests only) |
| `AISOLATE_TOOL_SERVER_URL` | `http://localhost:8081/mcp` | This airlock's MCP server; the controller sets `http://<space>-<airlock>-mcp-server:8081/mcp` and leaves it out when the airlock runs no MCP server |
| `AISOLATE_CODE_URL`, `AISOLATE_CODE_DIGEST`, `AISOLATE_CODE_TOKEN_FILE` | empty | The airlock's code: the backend's `http://backend.<namespace>.svc:8085/v1/code/<space>/<airlock>/<digest>`, its `sha256:` digest (`jar.digest`) and the airlock's code token the controller mounted, `/var/run/aisolate/code/token`. Fetched at start, retried while the backend is away, refused on another status or other bytes ([The code](#the-code)); all unset for an airlock without code |
| `AISOLATE_PLUGINS_DIR`, `AISOLATE_PLUGIN_DIGESTS` | empty | Local runs and tests only, without `AISOLATE_CODE_DIGEST`: a directory of code and the `sha256:` digests it must hold; nothing renders them in a cluster |
| `AISOLATE_DATASOURCE_<i>_ID`, `_URL`, `_USERNAME`, `_PASSWORD` | unset | Every data source the airlock resolves, global ones included, `<i>` from 0 in the order of the ids: id and URL as values, user and password from that source's Secret. A handler reaches one through `aisolate.dataSource(id)`, a PostgreSQL connection with a 5 s connect and a 10 s socket timeout |
| `AISOLATE_LLM` | required | The main LLM's id, the one that answers; always one of the `AISOLATE_LLM_<i>_ID`. Temperature 0.1, keep-alive unlimited, thinking off |
| `AISOLATE_LLM_<i>_ID`, `_PROVIDER`, `_URL`, `_MODEL` | required | Every LLM the airlock sees (its own, its space's and the global ones, the nearest of an id), `<i>` from 0 in the order of the ids, read until the first missing `_ID`. A blank one, an id twice or a provider other than `ollama` or `openai` refuses the start; no default. Code reaches one through `aisolate.llm(id)` |
| `AISOLATE_LLM_<i>_API_KEY` | unset | An `openai` LLM's API key, from its Secret: the bearer of its requests, none without one |
| `AISOLATE_MODEL_READ_TIMEOUT` | `90s` | A stuck model becomes an `error` event |
| `AISOLATE_EXCHANGE_MAX_DURATION` | `8m` | The budget of one exchange, checked before each model round and after each tool call |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | unset | The trace collector's OTLP/HTTP base URL; the pod appends `/v1/traces`. Unset or blank: no span leaves the pod ([Observability](observability.md#traces)) |
| `AISOLATE_AUDIT_URL`, `AISOLATE_AUDIT_TOKEN_FILE` | empty | The backend's audit ingest, `http://backend.<namespace>.svc:8085/v1/audit/events`, and the airlock's ingest token the controller mounted, `/var/run/aisolate/audit/token`, read for every send. Empty URL (headless): the lines go to stdout alone ([Audit](audit.md)) |
| `AISOLATE_AUDIT_BUFFER_DIR` | `/var/lib/aisolate/audit` | The lines not yet handed over, one file each, on the pod's `emptyDir`; at most 64 MB (`aisolate.audit.buffer-max`) |
| `AISOLATE_AUDIT_FAIL_CLOSED` | `true` | With the buffer full, refuse new questions with 503 `audit unavailable`; `false` answers on and drops the lines that do not fit |
| `AISOLATE_AUDIT_STDOUT` | `true` | Print every audit line to stdout too, for a SIEM; always on without an ingest |

## MCP server

| Variable | Default | Meaning |
|---|---|---|
| `AISOLATE_ISSUER_URI`, `AISOLATE_AUDIENCE` | as above, `aisolate` | Independent token validation |
| `AISOLATE_ROLES_CLAIM` | `realm_access.roles` | Where the rules read the user's roles |
| `AISOLATE_CONFIG_DIR`, `AISOLATE_CONFIG_REVISION` | required, empty | The same airlock's documents and revision as its airlock pod; the policy comes from there, parsed at start, and only that revision answers (any other is a silent deny) |
| `AISOLATE_CODE_URL`, `AISOLATE_CODE_DIGEST`, `AISOLATE_CODE_TOKEN_FILE` | empty | As for the airlock pod: the airlock's code, fetched at start and checked against its digest |
| `AISOLATE_PLUGINS_DIR`, `AISOLATE_PLUGIN_DIGESTS` | empty | As for the airlock pod: local runs and tests only |
| `AISOLATE_DATASOURCE_<i>_ID`, `_URL`, `_USERNAME`, `_PASSWORD` | none | Every data source the airlock resolves, the same entries as its airlock pod's; one connection pool per entry, the read-only role's user and password from that source's Secret |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | unset | As for the airlock pod |

At start the MCP server loads every plugin on its classpath and in the airlock's fetched code, and the rule sets
at `aisolate/rules/<entity>.json`. It publishes the tools the airlock lists, over the entities they declare,
each on its plugin's data source. It refuses to start, where the [deploy](api.md#the-deploy-call) refused the
same code before it reached a pod:

- without a plugin, or with a rule set at another path;
- with a tool name, an entity or a rule set twice: `tool read_records is published twice`,
  `entity hr.compensation is declared twice`, `two rule sets for entity hr.compensation`;
- with a plugin that declares entities and names no data source:
  `plugin hr-tools declares entities but names no data source`;
- with an entity whose row key is not one of its columns, or a rule whose `columns` or `row_filter` names a
  column its entity does not declare: `entity hr.leave: row key id is not a column`,
  `rule set hr.compensation: rule own-record names column ibna no plugin declares`;
- with a plugin whose data source the airlock does not resolve:
  `plugin hr-tools reads data source payroll, which this airlock does not define`.

## The pods of an airlock

What the controller renders for airlock `a` of space `s`, the same shape for both languages: only the images differ
(`aisolate/airlock` and `aisolate/tool-server`, or `aisolate/airlock-python` and `aisolate/tool-server-python` for a
Python project's `jar.language`), and the egress proxy's variables. An airlock without `llm` or `prompt` gets no pod;
the MCP server exists only when the airlock has code and lists a tool in `tools`. What both runtimes do with these
inputs is pinned, case by case, in [`conformance/cases`](../conformance/README.md).

| Object | Name |
|---|---|
| The airlock pod's Deployment, Service, Ingress rule and NetworkPolicy | `s-a-airlock` |
| The MCP server's Deployment, Service and NetworkPolicy | `s-a-mcp-server` |
| The documents, a ConfigMap (`Global-aisolate.yaml`, `Space-s.yaml`, `Airlock-a.yaml`) | `s-a-config` |
| The code token, a Secret with the key `token` | `s-a-code` |
| The audit ingest token, a Secret with the key `token` | `s-a-audit` |

| | Airlock pod | MCP server |
|---|---|---|
| Container | `airlock` | `tools` |
| Ports | `http` 8080 (Service 8080), `management` 9464 | `http` 8081 (Service 8081), `management` 9464 |
| Replicas, strategy | `platform.airlock.replicas`, `Recreate` | 1, `Recreate` |
| Resources | `platform.airlock.resources` | `platform.toolServer.resources` |

- **Probes and metrics**, on 9464 only and without a token: readiness `GET /actuator/health/readiness` (after 10 s,
  every 5 s, 30 failures), liveness `GET /actuator/health/liveness` (after 60 s, every 10 s), answered 200
  `{"status":"UP"}` or 503 `{"status":"DOWN"}`, and `GET /actuator/prometheus`. A pod labelled
  `aisolate.dev/scraper: "true"`, in any namespace, may reach 9464; no Service exposes it.
- **Security context**: no privilege escalation, a read-only root filesystem, every capability dropped;
  `runAsNonRoot` (the images run as the numeric user `10001:10001`), seccomp `RuntimeDefault`, no service-account
  token; `platform.airlock.runtimeClassName` and the `platform.scheduling` node selector and tolerations when set.
  The only writable paths are emptyDirs: `/tmp` (2 GiB; 256 MiB on the discovery pods), which holds the fetched code,
  and the audit buffer.
- **Labels and annotations**: `aisolate.dev/role` (`airlock`, `tool-server`, `discovery`), `aisolate.dev/space`,
  `aisolate.dev/airlock` and `aisolate.dev/managed-by: controller` on every object; the pod template's
  annotation `aisolate.dev/revision: <revision>`, so a new revision rolls the pods and a sibling's change rolls none.
  The Ingress rule (path prefix `/v1/spaces/s/airlocks/a/`) carries `nginx.ingress.kubernetes.io/proxy-read-timeout:
  "600"` and `nginx.ingress.kubernetes.io/proxy-buffering: "off"`: an exchange streams for minutes.

| Mount | Path | Pods |
|---|---|---|
| `config`, the ConfigMap `s-a-config`, read-only | `/etc/aisolate`, `AISOLATE_CONFIG_DIR` | both |
| `code-token`, the Secret `s-a-code`, read-only | `/var/run/aisolate/code/token`, `AISOLATE_CODE_TOKEN_FILE` | both, when the airlock has code and the install a backend |
| `tmp`, emptyDir | `/tmp` | both |
| `audit-token`, the Secret `s-a-audit`, read-only | `/var/run/aisolate/audit/token`, `AISOLATE_AUDIT_TOKEN_FILE` | the airlock pod, with the audit ingest |
| `audit-buffer`, emptyDir of 96 MiB | `/var/lib/aisolate/audit`, `AISOLATE_AUDIT_BUFFER_DIR` | the airlock pod, with the audit ingest |

The controller derives both tokens from the install's ingest key (`AISOLATE_CONTROLLER_AUDIT_INGESTKEY`, the chart's
Secret `audit-ingest`): the audit token is `base64url_nopad(HMAC-SHA256(key, "s/a"))`, the code token the same over
`"code/s/a"`, so each admits one airlock's lines or one airlock's code and nothing else. A pod only reads the file. An
`openai` LLM's API key and a data source's login reach the pods as variables from their own Secrets
(`AISOLATE_LLM_<i>_API_KEY`, `AISOLATE_DATASOURCE_<i>_USERNAME`, `_PASSWORD`), never as files.

### The code

No code object reaches the cluster: the backend keeps an airlock's artifact (a jar, or a Python project's zip) and
serves it on its cluster-internal port 8085, never on the API or the Ingress. A pod whose airlock has code, before it
serves anything:

1. sends `GET AISOLATE_CODE_URL` (`<backend>/v1/code/s/a/<digest>`) with the bearer read from
   `AISOLATE_CODE_TOKEN_FILE`. A connection failure, a 429 or a 5xx is retried, six tries with 1, 2, 4, 8 and 16 s
   between them; any other answer refuses at once: `fetching the code from <url> answered <status>` (401 no token,
   403 another airlock's token, 404 the airlock holds other code now);
2. streams the body under `/tmp` (a jar to `/tmp/aisolate/code/aisolate-code.jar`, a Python zip to
   `/tmp/aisolate/fetched/aisolate-code.zip`) and hashes it on the way; other bytes than `AISOLATE_CODE_DIGEST` refuse
   (`the code fetched from <url> has digest <d>, not AISOLATE_CODE_DIGEST <expected>`) and leave no file;
3. loads it, the jar's classes or the zip's package ([The Python artifact](python-artifact.md#delivery-and-loading-the-pods)).

Any refusal fails the start, and readiness never says UP before the code is loaded; Kubernetes restarts the pod. A
digest without `AISOLATE_CODE_URL` refuses too: `the airlock has code (AISOLATE_CODE_DIGEST) but no AISOLATE_CODE_URL
to fetch it from: code needs the backend (headless=false)`.

### Start checks

Both pods refuse to start, naming the problem, on: a data source variable left blank (`AISOLATE_DATASOURCE_<i>_ID is
blank`, `…_URL is blank`) or an id twice (`data source <id> is defined twice`); in the airlock pod, an LLM catalog
that does not hold together (`AISOLATE_LLM is blank`, `AISOLATE_LLM_<i>_ID is blank`, `…_URL is blank`, `…_MODEL is
blank`, `model <id> is defined twice`, `model <id>: provider <p> is not one of [ollama, openai]`, `AISOLATE_LLM granite
is none of the AISOLATE_LLM_<i>_ID`; a blank `_API_KEY` is none). Indexes run from 0 and are read until the first
missing `_ID`. Data source URLs are JDBC URLs (`jdbc:postgresql://host:port/db`); the login comes from the variables,
never from the URL. The documents must load whole, without an unknown key, a dangling reference or a revision
mismatch: the revision is the first 12 hex digits of a SHA-256 over every document in (kind, name) order, each as
`<kind>/<name>`, a zero byte and the file's bytes, and a pod whose files hash to another refuses with `revision
mismatch: AISOLATE_CONFIG_REVISION says <x>, the documents hash to <y>`. No partial configuration is ever served.

### Tokens

Both pods validate every request's bearer themselves: the signature against the issuer's JWKS (the algorithm the key
names; never `none` or an HMAC), `iss` exactly `AISOLATE_ISSUER_URI`, `aud` holding `aisolate`, `exp` and `nbf` with
60 s of skew, a non-blank `sub`. `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`, when the chart sets it, names
the JWKS in the cluster instead of the issuer's discovery document. The airlock answers a missing or invalid token
401 `{"status":401,"title":"Unauthorized"}` (`application/problem+json`) with `WWW-Authenticate: Bearer
resource_metadata="<AISOLATE_PUBLIC_URL>/.well-known/oauth-protected-resource"`; only `/actuator/health/**`,
`/actuator/prometheus` and `/.well-known/**` (the RFC 9728 document: `resource` the public URL,
`authorization_servers` the issuer, `scopes_supported` `aisolate`) answer without one. CORS on `/v1/**` and
`/.well-known/**`: the configured origins, methods GET, POST, PUT and OPTIONS, headers `Authorization`,
`Content-Type`, `Accept` and `If-Match`, `AIsolate-Cluster` exposed, no credentials, max-age 3600; a preflight needs
no token. The MCP server answers `POST /mcp` on 8081 (MCP streamable
HTTP, stateless, JSON responses) to its own airlock pod alone; a request without a valid token is 401 with no body.

### The chat endpoint

A chat request is checked in this order: the token (401), the body (400: a blank message or one over 4,000
characters), the audit buffer (503 `audit unavailable` while it is full and the pod fails closed), the airlock (404
`unknown airlock`: not served by this pod, or another space in the path), admission (403 `forbidden`). Only the 403
writes an audit line, a `refusal` with its reason (`role` or `client`); a 400, 401, 404 or the audit 503 writes none. The answers
and their bodies: [API, Errors](api.md#errors).

### Network and egress

- The airlock pod admits the ingress controller on 8080 and the scrapers on 9464. It may reach DNS, the host of
  every LLM it sees, of every data source it resolves and of the trace collector (the admin's peers,
  `platform.egress.hosts`), its MCP server on 8081, the backend's pods on 8085 alone (audit, code), the identity
  provider and, when enabled, the egress proxy (`aisolate.dev/role: egress-proxy`, port 3128).
- The MCP server admits its own airlock pod on 8081 and the scrapers on 9464. It may reach DNS, the data source
  hosts, the trace collector, the backend's pods on 8085 when the airlock has code, the identity provider and the
  proxy.
- With the proxy, the Java pods get `JDK_JAVA_OPTIONS` (`-Dhttps.proxyHost=egress -Dhttps.proxyPort=3128
  -Dhttp.nonProxyHosts=…`); the Python pods get `HTTPS_PROXY=http://egress:3128` and the same exceptions as
  `NO_PROXY`. The exceptions are the admin's mapped hosts, the identity provider's issuer and JWKS hosts (unless
  proxied), `localhost`, `*.svc` and `*.cluster.local`.

## Controller

`POD_NAMESPACE` is the namespace it watches. `/etc/aisolate/settings.yaml`, rendered by the chart into a
ConfigMap, holds the installation's settings under `aisolate.controller.*`: the ingress host, class and
public URL; the airlock and tool server images, their pull policy and `allowTags` (off, an image without a
digest refuses to bind and the controller does not start); the application users' identity provider's issuer, JWKS URI and
egress peer; the DNS and ingress peers; `egress.hosts`, the NetworkPolicy peer for each host the LLMs and the
data sources of the global level, a space or airlock may name (a `cidr` of `/8` or longer or selectors, never both,
and a port; a wider block or a missing port refuses to bind and the controller does not start); the egress proxy, its host, port and the domains it lets
through; the airlock pods' replicas and the runtime class of every pod; CORS origins; `observability.otlpEndpoint`, where the pods send
their traces; `audit.url`, `audit.failClosed` and `audit.stdout`, where and how the airlock pods hand over their
audit lines (an empty URL without the AIsolate UI); `code.url`, the backend's code endpoint the pods fetch an
airlock's code from (`http://backend.<namespace>.svc:8085/v1/code`, empty without the AIsolate UI).
`AISOLATE_CONTROLLER_AUDIT_INGESTKEY`, from the chart's Secret `audit-ingest`, is the key each airlock's ingest
token Secret and code token Secret are derived from. Label keys and hosts with dots are written
bracketed (`"[kubernetes.io/metadata.name]"`, `"[acme-db.aisolate.svc]"`), or the map binding splits
them.

## Clients

The chat reads `NEXT_PUBLIC_AISOLATE_URL` (the release host) and an optional
`NEXT_PUBLIC_AISOLATE_AIRLOCK_ID`; the identity provider is discovered, not configured
([`apps/chat`](../apps/chat/README.md)). The AIsolate UI reads `NEXT_PUBLIC_AISOLATE_URL`,
`NEXT_PUBLIC_OIDC_AUTHORITY` (the operators' provider: demo realm `aisolate`), `NEXT_PUBLIC_OIDC_CLIENT_ID` (`aisolate-ui`) and an optional
`NEXT_PUBLIC_TRACE_URL` (a trace viewer with `{trace_id}`, for an audit event's View trace) at build time, the
image's build arguments ([`apps/frontend`](../apps/frontend/README.md)). The footer's version is the `AISOLATE_VERSION` build argument (else the package version) and its Source link is `NEXT_PUBLIC_AISOLATE_SOURCE_URL`, so a fork's build points users at the fork's source (AGPL-3.0 section 13).
