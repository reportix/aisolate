<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/aisolate-white.png">
    <img src="docs/assets/aisolate-black.png" alt="AIsolate" width="480">
  </picture>
</p>
<p align="center"><strong>Control AI access to your data.</strong></p>
<p align="center"><a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg" alt="License: AGPL-3.0-only"></a></p>

AIsolate gives an organisation's own chat, agent or application a governed endpoint to a large language
model over the organisation's existing databases. Every request carries the application user's token from the
organisation's identity provider, and every tool call runs under that identity. Row and column access is
compiled into the SQL at the data boundary; the model enforces nothing.

## Install

```bash
helm repo add aisolate https://sonatype.reportix.com/repository/helm-public
helm install aisolate aisolate/aisolate --namespace aisolate --values values.yaml
```

`headless: false` in `values.yaml` adds the AIsolate UI, whose image you build yourself. Values and every
parameter: the [aisolate-chart](https://github.com/reportix/aisolate-chart) repository.

## First airlock

```bash
export AISOLATE_ENDPOINT=https://api.aisolate.example.com
aisolate create -s=hr-space -t=company-hr && cd company-hr
aisolate login
aisolate deploy
```

`create` writes a project from a template. `login` signs you in once, in your browser. `deploy` builds the project
and creates its airlock in the space an admin set up in the AIsolate UI. The whole walk-through, from installing the
CLI to trying the airlock: [docs/develop.md](docs/develop.md).

## Documentation

- [Concepts](docs/concepts.md): what an airlock is, who decides what, the vocabulary.
- [Install](docs/install.md): the helm install, and what the cluster, the identity provider, the databases and the model server must provide.
- [Develop](docs/develop.md): the CLI, your first airlock, its tools, handlers and rules.
- [Integrate](docs/integrate.md): call an airlock from a workflow tool such as n8n or KNIME.
- [API](docs/api.md): connect a client; every endpoint, the chat endpoint, SSE events, errors.
- [Security](docs/security.md): one request end to end, and where each guarantee is enforced.
- [Audit](docs/audit.md): the append-only, hash-chained audit trail and the report built from it.
- [Release](docs/release.md): a headless install, rolling it back, and how AIsolate itself is released.
- More in [docs/](docs); to contribute, [CONTRIBUTING.md](CONTRIBUTING.md); the website: [aisolate.ai](https://aisolate.ai).

## Repositories

| Repository | Holds |
|---|---|
| [`aisolate`](https://github.com/reportix/aisolate) (this one) | The airlock, the MCP server, the controller, the backend, the AIsolate UI, the CLI and its templates, the SDK, the sample chat and the CRDs |
| [`aisolate-chart`](https://github.com/reportix/aisolate-chart) | The Helm chart: its install steps, every parameter and its tests |
| [`aisolate-integrations`](https://github.com/reportix/aisolate-integrations) | An n8n node and a KNIME extension that ask an airlock as a machine client (Apache-2.0) |
