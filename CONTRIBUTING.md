# Contributing

## Before you start

Read `README.md`, then [docs/concepts.md](docs/concepts.md), [docs/architecture.md](docs/architecture.md) and
[docs/security.md](docs/security.md). A
change to behaviour updates the page in `docs/` that describes it, in the same merge request.

## Run it locally

The compose stack brings the customer database `acme` with its `hr` and `clients` schemas, seed and roles,
AIsolate's own PostgreSQL, and Keycloak (login themes under `docker/keycloak/themes`) with two realms:
`aisolate` (`docker/keycloak/realm-aisolate.json`), AIsolate's own, where the operators sign in to the UI and the CLI
(`andrew` and `nora` admins, `auditor`, `jasmine` a developer, `guest` with no role), and `acme` (`docker/keycloak/realm-acme.json`), the
organisation's, whose application users ask the airlocks (`eric`, `leo`, `nora`, `ivy`, `jasmine`). The integration
tests start the same images through Testcontainers.

```bash
docker compose -f docker/compose.yaml up -d
AISOLATE_DB_PASSWORD=aisolate ./gradlew :apps:backend:bootRun # :8080, runs the migrations
npm ci && npm run dev -w apps/frontend     # :3001
npm run dev -w apps/chat                   # :3000
```

Spaces and airlocks can be defined there. Nothing answers without a cluster, and a data source that
brings credentials is refused: only a cluster keeps them, as Secrets. Keycloak imports a realm only when the
realm is absent; after a realm change run
`docker compose -f docker/compose.yaml up -d --force-recreate keycloak`.
`docker/postgres/acme/` runs only on a fresh volume: after a change there,
`docker compose -f docker/compose.yaml down -v` applies it (it wipes the spaces too). The backend's schema is one
baseline migration: a database an older build migrated fails Flyway's validation at start, so drop it the same way.

The airlock core and the MCP server by hand, the way the controller runs them, with the variables of
[docs/configuration.md](docs/configuration.md):

```bash
AISOLATE_CONFIG_DIR=<documents> AISOLATE_AIRLOCK=hr ./gradlew :apps:airlock:bootRun
AISOLATE_CONFIG_DIR=<documents> ./gradlew :apps:tool-server:bootRun
./gradlew :apps:airlock:bootRun --args='validate <documents>'
```

Actuator answers on 9464 (`MANAGEMENT_SERVER_PORT`); the tool server's `bootRun` moves it to 9465, so both run
side by side. Both `bootRun` tasks set two data sources on the compose stack's `acme`, as `AISOLATE_DATASOURCE_<i>_ID`,
`_URL`, `_USERNAME` and `_PASSWORD`: `hr` as `aisolate_hr` (the `hr` schema) and `clients` as
`aisolate_clients` (the `clients` schema), what the two templates read; on this stack each role's password is its
name.
Both pods take `AISOLATE_PLUGINS_DIR` and `AISOLATE_PLUGIN_DIGESTS` for an airlock's code.
`<documents>` is a directory of the document kinds, for example the Space and Airlock of the
[example documents](docs/documents.md#example-documents), whose airlock is `hr`. Ollama must serve
`granite4.1:8b` and `nomic-embed-text`.

## Repository layout

| Directory | What |
|---|---|
| `apps/airlock/` | The airlock core, one airlock per pod: the chat and discovery controllers, the exchange and the agent loop, admission (the roles of the space and of the airlock, and the airlock's callers; the roles also select the rules), the model and MCP clients, the airlock's handlers from its code, fetched from the backend at start, with a `javax.sql.DataSource` per data source, SSE, `validate`. No database of its own. |
| `apps/tool-server/` | The MCP server: publishes the airlock's tools, runs each call through the gates and hands the tool a governed query on its plugin's data source (token, admission, tool gate, identity join, rules, SQL, evidence). Holds no customer code and no tool of its own. |
| `apps/controller/` | The controller: the resources and their status, CRD generation (`./gradlew :apps:controller:syncCrds` writes them to `chart/crds`), the renderer, release validation, one reconciler per kind. |
| `apps/backend/` | The AIsolate UI's API, with `headless=false`: spaces (developers, roles, LLM, data sources), airlocks (roles, callers, LLM, data sources) with their code (`JarInspector`: the tools with their plugin and data source, the handlers, the rule sets), the deploy call (one `Airlock` checked against its code and its space), cluster sync and the data sources' Secrets, and the audit trail: the ingest on its own port, the append-only table chained per airlock, its reading and verify ([docs/audit.md](docs/audit.md)). Owns the schema (Flyway). |
| `apps/cli/` | The `aisolate` CLI (picocli, GraalVM native image, macOS and Windows installers). `src/main/templates/` holds the four templates, `company-hr`, `company-sales`, `auditor` and `hospital`, each one airlock with its code and its `aisolate.yaml` ([docs/develop.md](docs/develop.md)). |
| `apps/frontend/` | The AIsolate UI (Next.js). |
| `apps/chat/` | The sample chat client of the airlock endpoint (Next.js): sign-in with PKCE, discovery, the SSE stream, the answer with its provenance. |
| `libs/sdk/` | What a project compiles against: `ToolPlugin` (with `dataSource()`), `Entity`, `Column`, `Tool`, `GovernedQuery`, `Filter`, `Result`, `Arguments`, the handler interfaces (`ContextPreparator`, `ConversationMemory`, `AnswerVerifier`) and `AIsolateManager` (`embed`, `dataSource(id)`). |
| `libs/config/` | The document kinds, the loader and its validation, the LLM and data sources an airlock resolves from the global level, its space and itself (`llmOf`, `dataSourcesOf`), the rules grammar, URL checks, project variables, the YAML format and the revision. Jackson only, no Spring. |
| `libs/identity/` | The resource-server `SecurityConfig` (JWT validation, the roles claim, CORS, the RFC 9728 document) and `Principal`. |
| `libs/plugins/` | Reading an airlock's code: `JarScan` finds the tool plugins and handlers by scanning the classes, in any package; the code fetch and digest pin every pod applies at start (`CodeFetch`, `JarDigests`). |
| `libs/audit-report/` | The AIsolate Audit report as one xBRL-XML instance (`AuditReport`, `AuditLine`), which the backend's audit export writes ([docs/audit.md](docs/audit.md#export)). |
| `libs/ui/` | `@aisolate/ui`: design tokens, primitives and the HTTP, OIDC and env helpers of the AIsolate UI. One npm workspace with `apps/frontend` and `apps/chat`, rooted at `package.json`. |
| `chart/` | The Helm chart: CRDs, templates, values and its README. |
| `tests/chart/` | The chart's tests. |
| `docker/` | The compose stack for the laptop. |
| `docs/` | The documentation, indexed in `README.md`. |

## Checks

```bash
./gradlew spotlessCheck test                                        # every module; Testcontainers needs Docker
npm run lint && npm run format && npm test && npm run build         # the web workspace, from the root
```

Tests run against real Keycloak and PostgreSQL containers and cover the flow end to end. `:apps:cli:test` first
compiles the templates' code and runs their tests against `libs/sdk` (`templateTest`). `./gradlew pythonTest`,
part of `check` and `build`, runs the Python modules' tests and the Python templates' with
[uv](https://docs.astral.sh/uv/), which must be on the PATH. A security property
gets a test that fails when it breaks; a fix without such a test is not finished. The chart is tested by
`tests/chart/test.sh`. CI (`.gitlab-ci.yml`) runs `check` (Spotless; lint, format, unit tests, build
and `npm audit` of the web workspace), `test` (`./gradlew test` with Docker-in-Docker; `./gradlew pythonTest` with uv;
`chart-test`, `tests/chart/test.sh`), `scan` (Trivy on dependencies and secrets) and `package` (`cli-windows-installer`,
the Windows CLI's installer, run by hand).

## Conventions

- Tests are named `given<State>_when<Action>_then<Outcome>` and structured with `// Arrange`, `// Act`,
  `// Assert`. The same logic over several inputs is one `@ParameterizedTest`.
- Comments say why, not what.
- Commits follow Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`), one concern
  each. A merge request names the guarantee it touches.
- Vocabulary: **airlock** (`hr-space/hr-contracts` in messages), **space**, **roles** (what admits: those a space lists may ask every airlock of it, those an airlock lists, that airlock alone), **developers** (the e-mails a space lists, who edit it in the AIsolate UI), **callers** (the OAuth clients an airlock admits), **data source** (a database the airlock's code reads, by
  id), **exchange** (one request and its answer), **AIsolate UI**. Not application, app or turn.
- No new dependency for what a few lines do. Versions are pinned (`gradle.lockfile`,
  `package-lock.json`) and scanned in CI.

## Chart

The Helm chart is in `chart/`; `chart/README.md` has the install steps and every parameter.

- `headless` decides only whether the AIsolate UI is installed. It gates templates and never names a folder.
- One folder per component, its NetworkPolicy included; values mirror the folders (`platform.backend` is
  `templates/platform/backend/`).
- Every value a template reads has a `## @param` line, and every `@param` is read.
- Images use the common image block; without `allowTags` a tag-only image fails the render.
- Object names, labels and selectors are API: the controller's peers and settings depend on them. The settings
  and the proxy's `envoy.yaml` live in pod templates, never in a ConfigMap.
- The chart holds no credentials, names no demo and installs no stand-in: the identity provider, the
  databases and the chat are the organisation's.

## License of contributions

AIsolate is licensed under the GNU Affero General Public License, version 3 only. Sign each commit off
(`git commit -s`), certifying the Developer Certificate of Origin (developercertificate.org). By contributing you
grant Reportix GmbH a perpetual, worldwide, non-exclusive, royalty-free licence to use, modify, sublicense and
distribute your contribution under the AGPL-3.0-only and under Reportix GmbH's commercial licence; you keep your
copyright. The commits of the initial history predate the sign-off; every commit from here on carries it.
