<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/aisolate-white.png">
    <img src="docs/assets/aisolate-black.png" alt="AIsolate" width="320">
  </picture>
</p>

# AIsolate

[![License: AGPL-3.0-only](https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg)](LICENSE)

AIsolate gives an organisation's own chat, agent or application a governed endpoint to a large language
model over the organisation's existing databases. Every request carries the application user's token from the
organisation's identity provider, and every tool call runs under that identity. Row and column access is
compiled into the SQL at the data boundary. The model enforces nothing.

The demo is a fictional company, Acme Financial Group, with two spaces and an airlock each. In the HR contracts
airlock its people ask about their employment contract: an employee reads only their own, the CEO reads every
one. In the Sales airlock the founders ask about client accounts, invoices and what was agreed with a client; an
employee does not see that airlock at all. A refusal reads "I don't know".

## What AIsolate guarantees

- **Identity end to end.** One token, validated by the airlock pod and again by its MCP server.
- **The model is never an enforcement point.** It receives text, tool schemas and the rows the user may see,
  never a token, a name or a role, and it executes nothing.
- **Rules are compiled into SQL at the data boundary.** One parameterised SELECT from an allow-list catalog;
  the model's filters can only narrow it.
- **Deny is silent.** An excluded row looks like a missing row; when every result is empty the answer is
  exactly "I don't know".
- **Figures are written in, never typed.** The model answers with references to the cells it read; AIsolate
  writes each value in and checks whom, which period and which unit the model says it is about. A number or a fact
  value the model typed itself is shown as unsourced ([docs/verification.md](docs/verification.md)).
- **Every exchange is on the record.** The airlock pod hands each exchange's audit line to AIsolate's backend,
  which keeps it append-only and hash-chained per airlock; a pod that cannot keep a line refuses new questions
  ([docs/audit.md](docs/audit.md)).
- **Context is developer code.** The `ContextPreparator` handlers in the airlock's code run before the model
  is asked. AIsolate stores no business data and runs no retriever of its own.

Where each is enforced: [docs/security.md](docs/security.md).

## How it fits together

An **airlock** is a governed endpoint inside a **space**. The space lists the roles that may ask every airlock of
it and holds the LLM and data sources its airlocks share; the airlock lists its own roles, which may ask it alone
([Roles](docs/concepts.md#vocabulary)), and its callers. Each airlock runs as its own pod pair in Kubernetes: the airlock pod (the endpoint, the agent
loop, the airlock's handlers) and its MCP server (the airlock's tools over the organisation's database, the
rules compiled into SQL). A **controller** turns the
`Space` and `Airlock` documents into those pods. A developer's project is one airlock: its
code, written against the AIsolate SDK, and its `Airlock` in `aisolate.yaml`; the `aisolate` CLI
creates it and deploys it into an airlock of the AIsolate UI. Whether an install has the AIsolate UI is the
admin's choice, `headless`: with it, the backend applies every save; headless, an admin applies the
documents through the organisation's own pipeline. See [docs/concepts.md](docs/concepts.md).

## Repositories

| Repository | Holds |
|---|---|
| `aisolate` (this one; the others are published beside it) | The airlock core, the MCP server, the controller, the backend, the AIsolate UI, the CLI with its `company-hr`, `company-sales`, `auditor` and `hospital` templates, the SDK and the shared libraries, the sample chat client of the airlock endpoint (`apps/chat`), the Helm chart (`chart/`) and its tests |
| `aisolate-reset` | A CLI that gives an install with the AIsolate UI a clean slate |
| `website` | The public site |

This repository's layout, directory by directory: [CONTRIBUTING.md](CONTRIBUTING.md#repository-layout).

## Quickstart

Install the chart, with `headless: false` in `values.yaml` for the AIsolate UI ([docs/install.md](docs/install.md)):

```bash
helm install aisolate ./chart --namespace aisolate --values values.yaml
```

In the AIsolate UI an admin defines the LLMs `granite` and `nomic` and the data sources `hr` and `clients` for
all of AIsolate (a project names them by id and holds no credential), and the spaces `hr-space` and
`acme-financial-group` with their roles; each deploy creates or updates its airlock, `hr-contracts` and `sales`,
with its own roles. Then, with the CLI ([docs/develop.md](docs/develop.md#install-the-cli)):

```bash
export AISOLATE_ENDPOINT=https://api.aisolate.example.com
aisolate create -s=hr-space -t=company-hr
cd company-hr
aisolate login
aisolate deploy
cd .. && aisolate create -s=acme-financial-group -t=company-sales && cd company-sales
aisolate deploy
```

The projects are Python, built with uv or Python 3.13; `--language=java` writes the same templates in Java.
The walk-through, the project layout and how to try it: [docs/develop.md](docs/develop.md).

## Documentation

- [Concepts](docs/concepts.md): what an airlock is, who decides what, the vocabulary, where LLM and data sources are defined, the stages, the components.
- [Architecture](docs/architecture.md): the repositories, the modules and what depends on what, the two runtimes under one contract, what runs in the cluster, how an airlock's code travels, one exchange in the code.
- [Data model](docs/data-model.md): the three documents, the backend's tables, what the controller renders from them, the audit trail's shape.
- [Design decisions](docs/design-decisions.md): why it is built this way, one decision at a time.
- [Install](docs/install.md): what the cluster, identity provider, databases and model server must provide.
- [Develop](docs/develop.md): the CLI, the project of one airlock, tools, handlers, rules, the AIsolate UI, trying an airlock.
- [Command-line interface](docs/cli.md): install the `aisolate` CLI on macOS or Windows, its commands, where it keeps the session, building it.
- [Release](docs/release.md): a headless install: documents, Secrets, hosts, validate, apply, status, roll back.
- [API](docs/api.md): connecting a client, every endpoint, the chat endpoint, SSE events, errors.
- [Observability](docs/observability.md): OTLP traces and Prometheus metrics for any backend (we recommend the OpenTelemetry Collector, Jaeger and Prometheus); one trace per exchange, what its spans hold and never hold; the metrics, the scraper's label, the example dashboard.
- [Integrate](docs/integrate.md): call an airlock from a workflow tool (n8n, KNIME): the JSON answer, the sign-in, the templates and the KNIME nodes in the `integrations` repository.
- [Security](docs/security.md): one request end to end, the gates, identity, rules to SQL, pod isolation.
- [Compliance](docs/compliance.md): the controls against OWASP's Top 10 for LLM applications, the EU AI Act, DORA and the NIST AI RMF, with what each leaves open.
- [Verification](docs/verification.md): grounded answers, every figure written in from the evidence and checked; what that proves and what it does not.
- [Documents](docs/documents.md): the three document kinds, the project file, rule sets, names and revision, validation.
- [LLMs and data sources](docs/llms-and-data-sources.md): the model entry, the three levels, how the documents, the API, the controller, the runtimes and the UI carry them, API keys.
- [Configuration](docs/configuration.md): environment variables per image, controller settings.
- [Audit](docs/audit.md): the audit trail AIsolate keeps (append-only, hash-chained, read in the AIsolate UI and the API), the AIsolate Audit XBRL taxonomy, one exchange's export as PDF, JSON or XBRL, validating it with an XBRL processor.
- [Chart](chart/README.md): install steps and every parameter.
- [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md).
- [AUTHORS.md](AUTHORS.md): the team behind AIsolate.

## License

AIsolate is free software under the GNU Affero General Public License, version 3 only (`LICENSE`, SPDX
`AGPL-3.0-only`). You may run, study, change and redistribute it; if you offer a modified AIsolate to others
over a network, you must offer them its source as well. Contributions are accepted under the DCO and the licence grant in [CONTRIBUTING.md](CONTRIBUTING.md).

Copyright (C) 2026 Reportix GmbH.
