# Architecture

How AIsolate is built: the repositories, the modules of this repository and what depends on what, the two
runtimes under one contract, what runs in the cluster, how an airlock's code travels from a developer's laptop to
its pods, and where in the code one exchange runs. The vocabulary is [Concepts](concepts.md); where each guarantee
is enforced is [Security](security.md); what is stored where is [Data model](data-model.md); why it is built this
way is [Design decisions](design-decisions.md).

## Repositories

| Repository | Holds | Licence |
|---|---|---|
| [`aisolate`](https://github.com/reportix/aisolate) (this one) | The airlock pod and the MCP server in Java and in Python, the controller, the backend, the AIsolate UI, the sample chat client (`apps/chat`), the `aisolate` CLI with its templates, the two SDKs, the shared libraries, the conformance cases, this documentation and the CRDs the controller generates (`crds/`) | AGPL-3.0-only |
| [`aisolate-charts`](https://github.com/reportix/aisolate-charts) | The Helm chart (the CRDs copied from this repository's `crds/`, the controller, the policies, the egress proxy; with `headless=false` the UI, the backend and its database) and its tests | AGPL-3.0-only |
| [`aisolate-integrations`](https://github.com/reportix/aisolate-integrations) | An n8n node and KNIME nodes that ask an airlock as a machine client | Apache-2.0 |

## The modules of this repository

One Gradle build (`settings.gradle.kts`, Java 25), one npm workspace (`package.json`: `libs/ui`,
`apps/frontend` and `apps/chat`) and four Python modules, each with its own `pyproject.toml` and `uv.lock`. Every app depends on
libraries only, never on another app; every library depends only on the ones below it.

```mermaid
flowchart BT
    subgraph libs["libs/"]
        sdk["sdk<br/>what a project compiles against"]
        config["config<br/>documents, loader, rules grammar, revision"]
        identity["identity<br/>JWT validation, Principal"]
        plugins["plugins<br/>code fetch, digest pin, class scan"]
        auditreport["audit-report<br/>the xBRL-XML report"]
    end
    subgraph apps["apps/"]
        airlock["airlock"]
        toolserver["tool-server"]
        backend["backend"]
        controller["controller"]
        cli["cli"]
    end
    plugins --> sdk
    plugins --> config
    auditreport --> config
    airlock --> sdk & config & identity & plugins
    toolserver --> sdk & config & identity & plugins
    backend --> sdk & config & identity & plugins & auditreport
    controller --> config
    cli --> sdk & config
```

| Module | Framework | Listens on | Does |
|---|---|---|---|
| `apps/airlock` | Spring Boot, Spring AI, the MCP Java SDK as client | 8080, management 9464 | One airlock's endpoint: token, admission, the handlers of its code, the agent loop, grounding, the audit line, the SSE stream. No database of its own. Also the `validate` command and the discovery pods. |
| `apps/tool-server` | Spring Boot, the MCP Java SDK as server | 8081, management 9464 | The MCP server of one airlock: its tool plugins, the gates, the rules compiled into one SELECT per call on the plugin's data source. |
| `apps/controller` | Spring Boot, Java Operator SDK, fabric8 | 8082 | Watches `Global`, `Space` and `Airlock`, validates, renders the pod pair and everything around it, reports status. Generates the CRDs into `crds/` (`syncCrds`). |
| `apps/backend` | Spring Boot, JDBC, Flyway, fabric8 | 8080 for the API, 8085 for the ingest | The AIsolate UI's API and the CLI's deploy endpoint; owns the schema; applies every save to the cluster; keeps the audit trail and serves the code to the pods. Only with `headless=false`. |
| `apps/cli` | picocli, GraalVM native image | — | `aisolate create`, `login`, `logout`, `build`, `deploy`; the four templates in Java and in Python. |
| `apps/frontend` | Next.js, React, Tailwind | 3000 (3001 in development) | The AIsolate UI. Signs in with PKCE in the browser and calls the backend with the token. |
| `apps/chat` | Next.js, React, Tailwind | 3000 | The sample chat client of the airlock endpoint: sign-in with PKCE, discovery, the SSE stream, the answer with its provenance. |
| `apps/airlock-python` | Starlette, uvicorn, `mcp` as client | 8080, 9464 | The airlock pod for a Python project: the same contract as `apps/airlock`. |
| `apps/tool-server-python` | Starlette, uvicorn, `mcp` as server, pg8000 | 8081, 9464 | The MCP server for a Python project: the same contract as `apps/tool-server`. |
| `libs/sdk`, `libs/sdk-python` | plain Java; plain Python | — | `ToolPlugin`, `Entity`, `Column`, `Tool`, `GovernedQuery`, `ContextPreparator`, `ConversationMemory`, `AnswerVerifier`, `AIsolateManager`; in Python the same as classes and decorators, plus `aisolate build`. |
| `libs/config` | Jackson, no Spring | — | The three document kinds, the loader and its validation, `llmsOf` and `dataSourcesOf` over the three levels, the rules grammar, URL checks, the revision. |
| `libs/identity` | Spring Security | — | The resource-server `SecurityConfig` (issuer, JWKS, audience, the roles claim, CORS, the RFC 9728 document) and `Principal`. |
| `libs/plugins` | plain Java | — | `CodeFetch`, `JarDigests`, `JarScan`, `CatalogCheck`: fetching the code by digest and finding the plugins and handlers in it. |
| `libs/runtime-python` | Starlette, PyJWT, pg8000, OpenTelemetry | — | What both Python pods share: the server shell, the documents, the token, admission, the artifact, the data source pool, telemetry. |
| `libs/audit-report` | plain Java | — | One exchange as an xBRL-XML instance of the AIsolate Audit taxonomy. |
| `libs/ui` | React, Tailwind | — | `@aisolate/ui`: tokens, primitives, the HTTP, OIDC and env helpers of the AIsolate UI. |

### Inside a Spring Boot app

Each of `airlock`, `tool-server` and `backend` has three packages, and an `ArchitectureTest` (ArchUnit) in each
keeps them apart:

| Package | Holds | May depend on |
|---|---|---|
| `application` | The HTTP controllers and the MCP endpoint: parse the request, call the domain, shape the response | `domain` |
| `domain` | The logic: the exchange, the gates, the rules, the SQL, the audit line, the services and repositories | the SDK and `libs/config`; nothing of Spring Web or Spring Security |
| `infrastructure` | The adapters: configuration, the model and MCP clients, the cluster client, the data sources, the error handler | anything |

The rules the tests pin are the security ones: the airlock's `chat` package cannot import Spring Security or
`libs/identity`, so the agent loop never holds a token, and only the MCP client reads a token's value; nowhere may
`org.keycloak` be imported, AIsolate speaks OIDC discovery and JWKS alone; the airlock pod has no Spring JDBC and
no write endpoint; the backend has no model client and no MCP client; every backend endpoint names who may call
it through one `Access` expression.

## Two runtimes, one contract

A project is Python or Java; its airlock runs on the matching images, `aisolate/airlock-python` and
`aisolate/tool-server-python` or `aisolate/airlock` and `aisolate/tool-server`. The controller renders one pod
shape for both and swaps only the image (`spec.jar.language`): the same environment variables, the same mounts,
the same ports, the same probes on 9464. What a pod must do with them is written down once, in
[The runtime contract](runtime-contract.md), and the Java runtime is the reference.

The guard is the conformance suite, `conformance/cases/*.json`: admission, token checks, the tool server's gates,
rule evaluation and the compiled statement, SQL safety, grounding, the audit line, the LLM catalog, the code fetch,
written as data once and fed to each runtime by a thin runner of its own. Neither image ships unless both runners
pass; a case that fails for one runtime is a divergence to fix, never a case to edit for one side.

| | Java | Python |
|---|---|---|
| Runtime | Java 25, Spring Boot | Python 3.13, Starlette and uvicorn, asyncio |
| Project code | a jar; the classes scanned, parent-first over the SDK | a zip with `aisolate-manifest.json`, the package and its vendored wheels; the package imported, then checked against the manifest |
| Database driver | JDBC, PostgreSQL | pg8000 (pure Python, DB-API 2.0) |
| Model client | Spring AI (`ollama`, `openai`) | httpx2, hand-written |
| MCP | MCP Java SDK, streamable HTTP | `mcp`, streamable HTTP, stateless server |
| Handlers and tools | synchronous | synchronous, run on a worker thread so one slow handler blocks no other exchange |
| Traces | Micrometer, OTLP/HTTP | OpenTelemetry SDK, a twenty-line OTLP/HTTP exporter |
| Discovery pods | yes | no: discovery always runs the Java image |

## In the cluster

```mermaid
flowchart TB
    subgraph ns["Namespace aisolate, default-deny"]
        direction TB
        Ingress["Ingress controller"]
        Ctl["controller"]
        Disc["discovery pods<br/>airlock image, the list alone"]
        subgraph pair["per airlock: s-a"]
            AP["s-a-airlock<br/>:8080"]
            MCP["s-a-mcp-server<br/>:8081"]
            CM[/"ConfigMap s-a-config<br/>Global, Space, Airlock"/]
            SC[/"Secret s-a-code"/]
            SA[/"Secret s-a-audit"/]
        end
        subgraph ui["with headless=false"]
            BE["backend<br/>:8080 API, :8085 ingest"]
            PG[("PostgreSQL")]
            FE["frontend"]
        end
        Egress["egress proxy (Envoy)<br/>:3128"]
    end
    CRs[("Global, Space, Airlock")]
    IdP["Identity provider"]
    DB[("Customer databases")]
    Model["Model server"]
    Ingress --> AP
    Ingress --> Disc
    Ingress --> BE
    Ingress --> FE
    Ctl -->|watches| CRs
    Ctl -->|renders| pair
    Ctl -->|renders| Disc
    BE -->|applies| CRs
    BE --> PG
    AP -->|MCP + token| MCP
    AP -->|audit line, code| BE
    MCP -->|code| BE
    AP --> DB
    MCP --> DB
    AP --> Model
    AP & MCP & Disc & BE -.->|JWKS| IdP
    AP & MCP -.->|CONNECT, proxied hosts| Egress
```

- **The controller** is one Deployment per install. It watches the three kinds with the Java Operator SDK, one
  reconciler each. For a valid `Airlock` it renders the airlock pod and, when the airlock has code and lists a
  tool, its MCP server; the documents' ConfigMap; the code token Secret and the audit token Secret; the Services,
  the Ingress rule for the airlock's path and one NetworkPolicy per pod. Everything is owned by the `Airlock`, so
  deleting it takes the pair with it. Per namespace it keeps the discovery list and pods, owned by every `Space`.
  Status goes back into the resource: `phase`, `conditions`, `problems`, `readyReplicas`, `revision`.
- **The pod pair** runs under the restricted Pod Security Standard: non-root (`10001`), no capabilities, read-only
  root filesystem, seccomp `RuntimeDefault`, no ServiceAccount token, `/tmp` an emptyDir. The airlock pod reaches
  its own MCP server, the hosts of its LLMs and data sources, the identity provider, the backend's ingest port and
  the trace collector; the MCP server reaches its data sources, the identity provider, the ingest port and the
  collector; nothing else, in or out. A host on the proxy list is reached through the egress proxy, CONNECT only.
- **The backend and the UI** exist only with `headless=false`. The backend keeps what the UI defines in its own
  PostgreSQL and applies it to the cluster after every write; its ingest port serves two things to pods and nothing
  else, the audit ingest and the code fetch. The UI runs in the browser against the backend's API.
- **Headless**, the organisation's pipeline applies the documents and creates the Secrets; the same controller
  renders the same pods, and an airlock with code does not start, since no backend holds it.

What each object is named, what each pod gets in its environment and mounts, and what each policy opens:
[Names, ids and revision](documents.md#names-ids-and-revision), [The runtime contract](runtime-contract.md#1-pod-inputs),
[Network policies](security.md#network-policies).

## How code travels

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer
    participant CLI as aisolate CLI
    participant BE as backend
    participant K8s as API server
    participant Ctl as controller
    participant Pod as airlock pod, MCP server
    Dev->>CLI: aisolate deploy
    CLI->>CLI: build: a jar (Gradle) or a zip with its manifest (uv)
    CLI->>BE: POST /v1/spaces/{space}/deploy, token, the artifact, aisolate.yaml, the variables
    BE->>BE: read the code (JarInspector, or the manifest), check the Airlock against it and against the space
    BE->>BE: save the airlock and the bytes in 1 MiB chunks, one transaction
    BE->>K8s: apply the Airlock with spec.jar.digest, the Secrets of its data sources
    Ctl->>K8s: watch
    Ctl->>K8s: render the pods with AISOLATE_CODE_URL, AISOLATE_CODE_DIGEST, the code token
    Pod->>BE: GET /v1/code/{space}/{airlock}/{digest}, code token
    Pod->>Pod: hash the bytes as they arrive, refuse to start on any other digest
    Pod->>Pod: scan or import the code, fail the start on a listed handler or tool it lacks
```

The code never travels through the API server or a ConfigMap, and no size limit applies but the operator's. The
backend reads a Java jar's classes to list its tools and handlers (the one place a project's Java code runs
outside its pods, [Residual risks](security.md#residual-risks)); a Python zip it reads by its manifest and never
imports. A deploy creates the airlock or updates it in place, so the pods roll and the endpoint stays. Steps and
classes: [The runtime contract](runtime-contract.md#3b-how-project-code-is-loaded-today-java), [The Python
artifact](python-artifact.md).

## One exchange, in the code

The steps are those of [One request, step by step](security.md#one-request-step-by-step); the gates are numbered as
there.

| Step | Java | Python |
|---|---|---|
| The request, gate 1 | `ChatController`; `SecurityConfig` of `libs/identity` | `aisolate_airlock.app`; `aisolate_runtime.identity`, `server` |
| Gate 2, admission | `AirlockAdmissionService` over `AirlockCatalog` | `aisolate_runtime.admission` |
| The exchange and the handlers | `ChatExchange`, `HandlerChain`, `Handlers`, `HandlerCatalog`; the handlers get an `AIsolateManager` from `ExchangeManagerFactory` | `aisolate_airlock.exchange` |
| The agent loop and the model | `AgentLoopService`; `LlmCatalog`, `OllamaLlm`, `OpenAiLlm` | `aisolate_airlock.llm`, `prompts` |
| Gate 3, the tools | `GovernedToolCallback`, `UnknownToolCallback`; `McpSyncToolClient` | `aisolate_airlock.mcp_client` |
| Gate 4, the MCP server's token | `SecurityConfig` of the tool server | `aisolate_runtime.identity` |
| Gate 5, policy, rules, SQL | `GovernedToolsConfig` → `GovernedQueryService` with `PolicySource`, `Policy`; `RulesEvaluator` → `Obligations`, `RowPredicate`; `ToolArgs`; `SqlCompiler` → `CompiledQuery`; `CustomerDataSources` | `aisolate_tool_server.app`, `governed`, `rules`, `sql`; `aisolate_runtime.datasources` |
| The evidence | `ToolEvidence`; `Result`, `Evidence` of the SDK | `aisolate_airlock.evidence` |
| Gate 6, grounding | `AnswerFormat`, `Grounding`, `GroundedAnswer` | `aisolate_airlock.answer_format`, `grounding` |
| Gate 7, the verifiers | `HandlerChain` over the `AnswerVerifier` handlers | `aisolate_airlock.exchange` |
| The stream | `SseStream`, `ChatEvent` | sse-starlette in `aisolate_airlock.app` |
| The audit line | `ExchangeAudit`, `AuditEvent`, `AuditLog`, `AuditSink` | `aisolate_airlock.audit` |
| The trace | `ExchangeTracing`, `OwnTraceFilter`; `TracingConfig` of `libs/identity` | `aisolate_airlock.tracing`; `aisolate_runtime.telemetry` |
| The documents and the revision | `ConfigurationLoader`, `Configuration`, `Names`, `Urls` of `libs/config` | `aisolate_runtime.config` |
| The code at start | `PluginsConfig` (both pods), `PluginLoader`, `PluginCatalog`, `RuleSetFiles`; `CodeFetch`, `JarDigests`, `JarScan`, `CatalogCheck` of `libs/plugins` | `aisolate_runtime.artifact`; `aisolate.build` `discover` and `check` |

The backend's side: one controller per resource kind (`SpaceController`, `AirlockController`,
`GlobalController`), the deploy (`DeployController` → `DeployService`, `JarInspector`, `ManifestReader`,
`DocumentForms`), the code fetch (`CodeController`), the audit (`AuditIngestController` on the ingest port;
`AuditController`, `AuditService`, `AuditExport`, `AuditRetention`), Monitoring (`TraceController`, `MetricsController`, `JaegerClient`, `PrometheusClient`),
and `ClusterSyncService`, which turns the rows into documents (`Documents`) and applies them. The controller's:
`AirlockReconciler`, `SpaceReconciler`, `GlobalReconciler`, the validation in `Release`, the rendering in
`Renderer`, the CRD generation in `CrdGeneration`. The CLI's: `AIsolateCli` with one class per command,
`Templates`, `GradleBuild` and `PythonBuild`, `DeployClient`, `BrowserLogin` and `Session`.

## Build, test, ship

| | Command | Runs |
|---|---|---|
| Java | `./gradlew check` | Spotless; every module's tests, the integration ones against real Keycloak and PostgreSQL containers (Testcontainers); the templates' code compiled and tested against the SDK (`templateTest`); `pythonTest` |
| Python | `./gradlew pythonTest` (part of `check`) | each Python module's tests and the Python templates', with uv; the conformance runners among them |
| Web | `npm run lint && npm run format && npm test && npm run build` | the workspace: ESLint, Prettier, Vitest, the Next.js build |
| CRDs | `./gradlew :apps:controller:syncCrds` | the three CRDs written into `crds/` from the controller's resource classes; the chart, its tests and its pin script, which copies them, are in `aisolate-charts` |
| CI | `.gitlab-ci.yml` | `check`, `test` (Docker-in-Docker for Testcontainers) and a version gate (every merge request bumps `version=` in `gradle.properties`) on every pipeline, `package` (`cli-windows-installer`, the Windows CLI's installer) by hand; every merge to `main` publishes the images as `<version>-<sha10>` to the internal registry through the fleet's release component, and the fleet's vulnerability scan runs on `main`; a tag `X.Y.Z` publishes the public images ([Release](release.md#how-aisolate-itself-is-released)) |

Images: the Java apps on a distroless Java 25 base and the Python apps on a distroless Python 3.13 base, both
pinned by digest, neither with a shell or a package manager (the Python dependencies come from a
`python:3.13-slim` build stage with `uv sync --frozen`), the UI on `node:24-alpine` as a standalone Next.js build; every one a numeric non-root user,
the Python dependency tree checked for licences in the image build. Versions are pinned (`gradle.lockfile`
per module, `uv.lock`, `package-lock.json`) and scanned in CI. Every dependency is permissive (Apache, MIT, BSD,
PSF, ISC); the product itself is AGPL-3.0-only ([Design decisions](design-decisions.md#permissive-dependencies-only)).

## Read next

- [Data model](data-model.md): the documents, the backend's tables and what the controller makes of them.
- [Design decisions](design-decisions.md): why it is built this way.
- [The runtime contract](runtime-contract.md): every environment variable, mount, endpoint and check a pod must honour.
- [Contributing](../CONTRIBUTING.md): running it on a laptop, the layout, the conventions.
