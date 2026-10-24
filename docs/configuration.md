# Configuration

In a cluster the controller sets each pod's variables from the airlock's documents, the Secrets and the
installation settings the chart renders; nothing is typed per pod. The chart's values are in the
chart README (`chart/README.md`). The defaults below are those of the compose stack a developer
runs on a laptop.

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
| `AISOLATE_CORS_ALLOWED_ORIGINS` | `*` | Browser origins (the AIsolate UI's); the token decides, not the origin |
| `AISOLATE_CLUSTER_NAMESPACE` | empty | The namespace the backend applies the resources to and creates the Secrets in. Empty: no cluster, a deploy ends in the database and a data source that brings credentials is refused |
| `AISOLATE_CLUSTER_RESYNC` | `PT5M` | How often the backend applies every space again, so a save the cluster did not take (an API server away) heals |
| `AISOLATE_API_DOCS` | `false` | `true` serves the OpenAPI document of the AIsolate UI's API and its Swagger UI, to anyone who reaches the backend (the operations still need the token) |
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
| `AISOLATE_CODE_URL`, `AISOLATE_CODE_DIGEST`, `AISOLATE_CODE_TOKEN_FILE` | empty | The airlock's code: the backend's `http://backend.<namespace>.svc:8085/v1/code/<space>/<airlock>/<digest>`, its `sha256:` digest (`jar.digest`) and the airlock's code token the controller mounted, `/var/run/aisolate/code/token`. Fetched at start, retried while the backend is away, refused on another status or other bytes ([runtime contract §1.4.1](runtime-contract.md)); all unset for an airlock without code |
| `AISOLATE_PLUGINS_DIR`, `AISOLATE_PLUGIN_DIGESTS` | empty | Local runs and tests only, without `AISOLATE_CODE_DIGEST`: a directory of code and the `sha256:` digests it must hold; nothing renders them in a cluster |
| `AISOLATE_DATASOURCE_<i>_ID`, `_URL`, `_USERNAME`, `_PASSWORD` | unset | Every data source the airlock resolves, global ones included, `<i>` from 0 in the order of the ids: id and URL as values, user and password from that source's Secret. A handler reaches one through `aisolate.dataSource(id)`, a PostgreSQL connection with a 5 s connect and a 10 s socket timeout |
| `AISOLATE_LLM` | required | The main LLM's id, the one that answers; always one of the `AISOLATE_LLM_<i>_ID`. Temperature 0.1, keep-alive unlimited, thinking off |
| `AISOLATE_LLM_<i>_ID`, `_PROVIDER`, `_URL`, `_MODEL` | required | Every LLM the airlock sees (its own, its space's and the global ones, the nearest of an id), `<i>` from 0 in the order of the ids, read until the first missing `_ID`. A blank one, an id twice or a provider other than `ollama` or `openai` refuses the start; no default. Code reaches one through `aisolate.llm(id)` |
| `AISOLATE_LLM_<i>_API_KEY` | unset | An `openai` LLM's API key, from its Secret: the bearer of its requests, none without one |
| `AISOLATE_MODEL_READ_TIMEOUT` | `90s` | A stuck model becomes an `error` event |
| `AISOLATE_EXCHANGE_MAX_DURATION` | `8m` | The budget of one exchange, checked before each model round and after each tool call |
| `AISOLATE_API_DOCS` | `true` | The OpenAPI document; the controller sets `false` |
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
