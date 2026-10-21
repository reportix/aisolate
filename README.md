<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/aisolate-white.png">
    <img src="docs/assets/aisolate-black.png" alt="AIsolate" width="320">
  </picture>
</p>

# AIsolate

[![License: AGPL-3.0-only](https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg)](LICENSE)

AIsolate gives an organisation's own chat, agent or application a governed endpoint to a large language
model over the organisation's existing databases. Every request carries the end user's token from the
organisation's identity provider, and every tool call runs under that identity. Row and column access is
compiled into the SQL at the data boundary. The model enforces nothing.

The demo is a fictional company, Acme Financial Group, with two spaces and an airlock each. In the HR contracts
airlock its people ask about their employment contract: an employee reads only their own, the CEO reads every
one. In the Sales airlock the founders ask about client accounts, invoices and what was agreed with a client; an
employee does not see that airlock at all. A refusal reads "I don't know".

## How it fits together

An **airlock** is a governed endpoint inside a **space**. The space lists the users who may ask every airlock of
it and holds the LLM and data sources its airlocks share; the airlock lists its own users, who may ask it alone
([Users](docs/concepts.md#vocabulary)), and its callers. Each airlock runs as its own pod pair in Kubernetes: the airlock pod (the endpoint, the agent
loop, the airlock's handlers) and its MCP server (the airlock's tools over the organisation's database, the
rules compiled into SQL). A **controller** turns the
`Space` and `Airlock` documents into those pods. A developer's project is one airlock: its
code, written against the AIsolate SDK, and its `Airlock` in `aisolate.yaml`; the `aisolate` CLI
creates it and deploys it into an airlock of the AIsolate UI. Whether an install has the AIsolate UI is the
admin's choice, `headless`: with it, the backend applies every save; headless, an admin applies the
documents through the organisation's own pipeline. See [docs/concepts.md](docs/concepts.md).

## Quickstart

Install the chart, with `headless: false` in `values.yaml` for the AIsolate UI ([docs/install.md](docs/install.md)):

```bash
helm install aisolate ./chart --namespace aisolate --values values.yaml
```

In the AIsolate UI an admin defines the LLMs `granite` and `nomic` and the data sources `hr` and `clients` for
all of AIsolate (a project names them by id and holds no credential), and the spaces `hr-space` and
`acme-financial-group` with their users; each deploy creates or updates its airlock, `hr-contracts` and `sales`,
with its own users. Then, with the CLI ([docs/develop.md](docs/develop.md#install-the-cli)):

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
- [Install](docs/install.md): what the cluster, identity provider, databases and model server must provide.
- [Develop](docs/develop.md): the CLI, the project of one airlock, tools, handlers, rules, the AIsolate UI, trying an airlock.
- [Command-line interface](docs/cli.md): install the `aisolate` CLI on macOS or Windows, its commands, where it keeps the session, building it.
- [Release](docs/release.md): a headless install: documents, Secrets, hosts, validate, apply, status, roll back.
- [API](docs/api.md): connecting a client, every endpoint, the chat endpoint, SSE events, errors.
- [Observability](docs/observability.md): OTLP traces and Prometheus metrics for any backend (we recommend the OpenTelemetry Collector, Jaeger and Prometheus); one trace per exchange, what its spans hold and never hold; the metrics, the scraper's label, the example dashboard.
- [Integrate](docs/integrate.md): call an airlock from a workflow tool (n8n, KNIME): the JSON answer, the sign-in, the templates and the KNIME nodes in the `integrations` repository.
- [Verification](docs/verification.md): grounded answers, every figure written in from the evidence and checked; what that proves and what it does not.
- [Documents](docs/documents.md): the three document kinds, the project file, rule sets, names and revision, validation.
- [LLMs and data sources](docs/llms-and-data-sources.md): the model entry, the three levels, how the documents, the API, the controller, the runtimes and the UI carry them, API keys.
- [Configuration](docs/configuration.md): environment variables per image, controller settings.
- [Audit](docs/audit.md): the audit trail AIsolate keeps (append-only, hash-chained, read in the AIsolate UI and the API), the AIsolate Audit XBRL taxonomy, one exchange's export as PDF, JSON or XBRL, validating it with an XBRL processor.
- [AUTHORS.md](AUTHORS.md): the team behind AIsolate.

## License

AIsolate is free software under the GNU Affero General Public License, version 3 only (`LICENSE`, SPDX
`AGPL-3.0-only`). You may run, study, change and redistribute it; if you offer a modified AIsolate to others
over a network, you must offer them its source as well.

Copyright (C) 2026 Reportix GmbH.
