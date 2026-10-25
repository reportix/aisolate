# Architecture

How AIsolate is built: the modules of this repository and what depends on what, the two
runtimes under one contract, what runs in the cluster, how an airlock's code travels from a developer's laptop to
its pods. The vocabulary is [Concepts](concepts.md); where each guarantee
is enforced is [Security](security.md); what is stored where is [What AIsolate stores](#what-aisolate-stores); why it is built this
way is [Design decisions](design-decisions.md).

## The modules of this repository

One Gradle build (`settings.gradle.kts`, Java 25), one npm workspace (`package.json`: `libs/ui`,
`apps/frontend` and `apps/chat`) and four Python modules, each with its own `pyproject.toml` and `uv.lock`. Every app depends on
libraries only, never on another app; every library depends only on other libraries.

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

## Two runtimes, one contract

A project is Python or Java; its airlock runs on the matching images, `aisolate/airlock-python` and
`aisolate/tool-server-python` or `aisolate/airlock` and `aisolate/tool-server`. The controller renders one pod
shape for both and swaps only the image (`spec.jar.language`): the same environment variables, the same mounts,
the same ports, the same probes on 9464 ([The pods of an airlock](configuration.md#the-pods-of-an-airlock)). The Java
runtime is the reference.

The guard is the conformance suite, `conformance/cases/*.json`: admission, token checks, the tool server's gates,
rule evaluation and the compiled statement, SQL safety, grounding, the audit line, the LLM catalog, the code fetch,
written as data once and fed to each runtime by a thin runner of its own. Neither image ships unless both runners
pass; a case that fails for one runtime is a divergence to fix, never a case to edit for one side.

## In the cluster

- **The controller** is one Deployment per install. It watches the three kinds with the Java Operator SDK, one
  reconciler each. For a valid `Airlock` it renders the airlock pod and, when the airlock has code and lists a
  tool, its MCP server; the documents' ConfigMap; the code token Secret and the audit token Secret; the Services,
  the Ingress rule for the airlock's path and one NetworkPolicy per pod.
- **The pod pair** runs under the restricted Pod Security Standard.
  The airlock pod reaches
  its own MCP server, the hosts of its LLMs and data sources, the identity provider, the backend's ingest port and
  the trace collector; the MCP server reaches its data sources, the identity provider, the ingest port and the
  collector; nothing else, in or out. A host on the proxy list is reached through the egress proxy, CONNECT only.
- **The backend and the UI** exist only with `headless=false`. The backend keeps what the UI defines in its own
  PostgreSQL and applies it to the cluster after every write; its ingest port serves two things to pods and nothing
  else, the audit ingest and the code fetch. The UI runs in the browser against the backend's API.
- **Headless**, the organisation's pipeline applies the documents and creates the Secrets; the same controller
  renders the same pods.

What each object is named, what each pod gets in its environment and mounts, and what each policy opens:
[Names, ids and revision](documents.md#names-ids-and-revision), [The pods of an airlock](configuration.md#the-pods-of-an-airlock),
[Network policies](security.md#network-policies).

## How code travels

The
backend reads a Java jar's classes to list its tools and handlers (the one place a project's Java code runs
outside its pods, [Residual risks](security.md#residual-risks)); a Python zip it reads by its manifest and never
imports. A deploy creates the airlock or updates it in place, so the pods roll and the endpoint stays. Steps and
details: [The code](configuration.md#the-code), [The Python artifact](python-artifact.md).

## What AIsolate stores

No business data lives in any of these stores: the organisation's rows and passages stay in its databases, read at
the moment of an exchange and never kept. What the controller renders from the documents is in
[Release](release.md#what-the-controller-renders), the audit trail's line and table in [Audit](audit.md#storage).

### Three stores

| Store | Holds | Owned by | Exists |
|---|---|---|---|
| The documents | `Global`, `Space`, `Airlock`: what runs, who may ask, which LLMs and data sources, the code's digest | the cluster; written by the backend or the organisation's pipeline | always |
| The backend's PostgreSQL | what the AIsolate UI defines (spaces, airlocks, the global LLMs and data sources), each airlock's code bytes, and the audit trail | the backend, which runs its Flyway migrations (the V1 baseline and `V2__roles.sql`) | with `headless=false` |
| Secrets | each data source's user and password, each `openai` LLM's API key; per airlock the code token and the audit token | the document that defines the data source or LLM; the controller for the two tokens | always |

The documents are the interface between the two halves: the backend writes them, the controller reads them, and
nothing a pod needs comes from anywhere else. A headless install writes them by hand and has no PostgreSQL.

## Read next

- [Design decisions](design-decisions.md): why it is built this way.
- [Configuration](configuration.md#the-pods-of-an-airlock): every environment variable, mount, port and check a pod must honour.
- [Contributing](../CONTRIBUTING.md): running it on a laptop, the layout, the conventions.
