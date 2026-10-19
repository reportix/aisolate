# Release to a headless install

A headless install runs no AIsolate UI and no backend. Its documents are the release: the admin writes them,
creates the Secrets they name, maps their hosts, validates them and applies them through the organisation's own review. The merge
request is the review, the apply is the release, the git history is the record. AIsolate stores no approval
state. The same controller renders the same pods as in an install with the AIsolate UI.

## Write the documents

`<space>.yaml` in the organisation's deployment repository: the `Space` and `Airlock`
documents, with URLs and Secret names, never a credential ([Example documents](documents.md#example-documents)).
An airlock's code is not in them: the pods fetch it from
the backend at start, so a
headless install, which has no backend, runs airlocks without code only; one with code does not start.

## Create the Secrets

One per `secretRef` the documents name, on the space or an airlock, with the keys `username` and
`password`:

```bash
kubectl create secret generic <secretRef> --namespace aisolate \
  --from-literal=username=<user> --from-literal=password=<password>
```

## Map the hosts

The host of every LLM URL and data source URL the `Global`, a space or an airlock names is in
`platform.egress.hosts` with its NetworkPolicy peer, or on `platform.egress.proxy.domains`. See the
chart README (`chart/README.md`).

## Validate the documents

```bash
docker run --rm -v "$PWD:/release:ro" aisolate/airlock validate /release
```

The command parses the directory, prints every problem and exits 1 or 0 before the application starts. It
skips documents of other API groups. The
rules are in [Validation](documents.md#validation).

## Apply

`kubectl apply -f <space>.yaml`, Argo CD or Flux.

## Check status

```bash
kubectl -n aisolate get spaces,airlocks
kubectl -n aisolate describe airlock hr
kubectl -n aisolate logs deploy/acme-hr-airlock
```

An `Airlock` is `Pending` until both of its pods rolled out, then `Ready`, or `Invalid` with its
problems. `kubectl get airlocks` shows each one's space, state, ready airlock pods and
revision. A `Space` reports whether its release is valid, and lists each of its airlocks
the controller holds back (`Airlock <name> is held back: …`); an `Airlock` without `llm` or `prompt` renders
nothing, and its status says so (`no llm: aisolate deploy brings it`).

## What the controller renders

Per `Airlock`, everything owned by it, named as [Names, ids and revision](documents.md#names-ids-and-revision)
lists:

| Object | Airlock pod | MCP server |
|---|---|---|
| Image | `aisolate/airlock`, pinned to its airlock; serves its airlock only | `aisolate/tool-server` |
| Loads | its airlock's ConfigMap (the `Global`, its `Space` and itself) and the airlock's code, fetched from the backend | its airlock's ConfigMap and the same code |
| Holds | every LLM the airlock sees and which is its main one; every data source it resolves, with the user and password from that source's Secret | every data source the airlock resolves, with the user and password from that source's Secret |
| Service | port 8080; the release's Ingress rule for its airlock path | port 8081; no route |
| Management port | 9464 (health, metrics), on no Service; a pod labelled `aisolate.dev/scraper` reaches it | the same |

- An airlock with code fetches it from the backend by `jar.digest` and refuses to start on any other bytes or a
  failed fetch; without a backend (headless) it does not start. An airlock without code fetches nothing.
- An airlock gets an MCP server only when it has code and lists a tool in `tools`.
- Both pods get the data sources, global ones included, as `AISOLATE_DATASOURCE_<i>_*`, sorted by id; the airlock
  pod gets its main LLM's id as `AISOLATE_LLM` and every LLM it sees as `AISOLATE_LLM_<i>_*`, sorted by id. The pods never resolve levels themselves.
- Once per namespace, owned by every `Space` so they go with the last one: discovery, `aisolate-discovery`.
  - Its ConfigMap holds, per valid space, only what discovery lists: each airlock's id, space, name, description, callers and
    tools, and its users as the SHA-256 of the lower-cased e-mail. No
    prompt, model, data source or Secret name.
  - Its Deployment (`aisolate.dev/role: discovery`) runs the airlock image with that ConfigMap alone: no
    documents, no code, no data source, no model. The release's `aisolate` Service selects these pods, so
    discovery, `/.well-known/` (headless) and a chat path no airlock's Ingress rule matches reach no airlock's
    code; such a chat path answers 404. The pods re-read the list when it changes and never restart for it.
  - Its NetworkPolicy admits the ingress controller on 8080 and a scraper on 9464, and lets out DNS, the
    identity provider, the egress proxy and the trace collector only.
- With `platform.observability.otlpEndpoint` set, both pods get it as `MANAGEMENT_OTLP_TRACING_ENDPOINT` and
  send their traces there ([Observability](observability.md#traces)).
- Each pod's egress is the admin's peer for each host it reaches: every catalog LLM's host for the airlock pod, every
  data source host and the trace collector's for both, or the egress proxy; the JVM sends HTTPS through the proxy for every host but the directly mapped ones, the identity
  provider's unless it is proxied, and the cluster's own (`*.svc`, `*.cluster.local`).
- Every container gets the requests and limits of `platform.airlock.resources` or
  `platform.toolServer.resources`, and its `/tmp` is capped at 256 MiB.

## What the controller checks

Each space is its own release: the `Space` and the `Airlock`s that name it. A problem holds back its own
scope and says why on the resource it names: an invalid `Airlock` is held back while its siblings run on, and
only a problem of the `Space` or of the install's settings holds back the whole space. So removing a user from
a `Space` reaches every valid airlock even while another airlock is broken. The problems:

- a problem the loader finds ([Validation](documents.md#validation));
- a data source an airlock resolves without its URL or its `secretRef`
  (`Airlock <a>: dataSources[<id>].url is required`, `… .secretRef is required`);
- a host, of an LLM URL or of a data source URL an airlock or its space defines, neither mapped in
  `platform.egress.hosts` nor proxied. Such a host of a global entry is a problem on the `Global`'s status
  instead, and that entry is left out of every airlock's catalog, so one global typo stops no space; an airlock
  whose main LLM it is is held back (`llm granite: host … is not in platform.egress.hosts`);
- a `jar.digest` that is not `sha256:`;
- a `platform.observability.otlpEndpoint` whose host is neither mapped nor proxied (every space:
  `platform.observability.otlpEndpoint host <h> is not in platform.egress.hosts`);
- a Service name over 63 characters.

A held-back airlock fails closed: the controller deletes everything it owns, so it serves no one until it is
valid again. Its pods would otherwise keep the last good `Space`, with a user removed since still
admitted. Its status gives the problems and `it serves no one until it is valid again`; its
`Space` names it. The other airlocks and spaces run on. An airlock whose `Space` is gone loses its pods the same
way.

## Roll back

Apply the previous documents; the controller rolls the pods back.

## Retire an airlock

Delete its `Airlock`. A deleted `Airlock` takes its ConfigMap, Services,
routes, policies and token Secrets along by owner reference. Delete with them the Secrets the deleted documents
name.

## See what ran

Every airlock pod logs each exchange: `exchange_start`, `handlers_resolved`, `handler_ran`, `tool_called`
(with the row count and the evidence id), `verifier_ran` (with the verdict), `exchange_done`; at start,
`config_loaded` with the revision. A refusal's reason is logged at WARN. The pod also writes the audit trail, one JSON line
per exchange and per refused request on its own logger `aisolate.audit` to stdout
([Audit](audit.md)). Logs name handlers and count rows; they carry no
rows, no passages and no token.
