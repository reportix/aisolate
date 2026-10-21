# Install AIsolate

AIsolate runs in a namespace of the organisation's Kubernetes cluster, from one Helm chart
(the chart in `chart/`). What the cluster, the identity provider, the databases and the model
server must provide comes first.

## Cluster requirements

- Kubernetes 1.29 or newer, with a CNI that enforces NetworkPolicy (Calico, Cilium). A CNI without policy
  support enforces nothing and says nothing.
- The namespace `aisolate`, labelled `pod-security.kubernetes.io/enforce=restricted`. Every pod runs under the
  restricted Pod Security Standard.
- An ingress controller for the release host. A chat stream sends nothing while the model thinks, up to 90 s:
  every proxy and load balancer in front of it needs an idle timeout above that (an AWS ALB's default is 60 s).
- Optional: a runtime class (gVisor, Kata) for every pod the controller renders, airlock, MCP server and
  discovery, `platform.airlock.runtimeClassName`.

## Identity provider requirements

AIsolate ships no identity provider. Its realm roles are two, `aisolate-admin` and `aisolate-auditor`: a
space's developers are named in the space, not in the identity provider. A space lists its users by e-mail, each
with the role Developer or User, and an airlock lists its own. A space's users may ask every airlock of the space; an airlock's own users may ask that airlock only
([Users](concepts.md#vocabulary)). Any OpenID Connect provider authenticates them (Keycloak, Microsoft Entra ID, Okta).

| Requirement | Detail |
|---|---|
| OIDC discovery and JWKS | AIsolate validates tokens with the published keys and calls the provider for nothing else. |
| The audience `aisolate` | Every token AIsolate accepts carries `aud: aisolate`. In Keycloak: an audience mapper that every client gets as a realm default, registered ones too. |
| `email` | Every end user's token carries it, and the provider issues only verified addresses: an airlock admits the e-mails its space's `access.users` and its own list by matching the token's `email`, and AIsolate does not check `email_verified`. The identity join matches the same e-mail to the organisation's records. |
| A roles claim | `realm_access.roles` by default; the chart's `platform.identity.rolesClaim` names another dot path (`roles` for Microsoft Entra ID app roles, `groups` for Okta), which the backend, the airlock pods and the MCP servers then read as `AISOLATE_ROLES_CLAIM`. The organisation's own roles (`employee`, `ceo`, `founder`) are what the rules name, and they never admit anyone; `aisolate-developer` and `aisolate-admin` admit AIsolate UI and CLI users, `aisolate-auditor` the readers of the audit trail alone. |
| Public PKCE clients | `aisolate-ui` for the AIsolate UI; `aisolate-cli` with the redirect `http://127.0.0.1/*` and offline sessions, so the CLI's session lasts 90 days. |
| The chat's client | Dynamic client registration (RFC 7591) with the chat's host trusted, or a registered public PKCE client. An airlock admits it by its client id in `access.callers`, or with `*`. |

The demo realm `docker/keycloak/realm-acme.json` is a complete example.

## Database roles

Each data source connects as its own role, with `statement_timeout` set on the role:

| The role of a data source read by | Role grants |
|---|---|
| A tool plugin alone | SELECT on the entities' tables, with `default_transaction_read_only` set on the role. |
| A context preparator too | SELECT on the entities' tables and the documents, UPDATE of the embedding column only; no read-only default, which would refuse that UPDATE. |

The `company-hr` and `company-sales` templates read one database, `acme`, one schema each: `company-hr` reads `hr`, the
`hr` schema, as `aisolate_hr` (a 5 s statement timeout; SELECT on `hr.documents`, the employment contracts, and
UPDATE of its `embedding`); `company-sales` reads `clients`, the `clients` schema, as `aisolate_clients`.
Neither role can read the other schema. The `auditor` and `hospital` templates name no database: the
`auditor` airlock reads the data source `ledger` it defines (one database and role per audited
company), the `hospital` airlock the space's `ehr`, shared by its departments.

## Model server

Ollama, reachable from the airlock pods at the LLM URL each airlock resolves, from itself or its
space. The templates set no provider, URL or model: set them on the space or the airlock. Their
comments name `granite4.1:8b` and `nomic-embed-text`; a model nobody sets is the airlock pod's default. Ollama
has no authentication: run it where it has no egress of its own.

## Install the chart

```bash
kubectl create namespace aisolate
kubectl label namespace aisolate pod-security.kubernetes.io/enforce=restricted
helm install aisolate ./chart --namespace aisolate --values values.yaml
```

The values file carries every AIsolate image by digest, `headless` (the default `true` installs no
AIsolate UI; `false` adds the UI, its backend and the backend's database), the identity provider under
`platform.identity`, and under `platform.egress` the cluster's DNS and ingress peers and the hosts of the LLM
and of every data source a space or an airlock names. The steps and every parameter are in the
chart README (`chart/README.md`).

The backend's database keeps the spaces, the airlocks and the audit trail on the PersistentVolumeClaim `db`
(`persistence.postgres.storage.size`, default `8Gi`; `.storageClass`, empty for the cluster's default), so it
outlives its pod; `helm uninstall` deletes it. The audit is only as durable as that database: back it up. A
headless install has no backend and keeps the audit on the pods' stdout only (`docs/audit.md`).

The same database keeps every airlock's code, the artifact a deploy brings (a jar, or a Python zip with its
dependencies), which each pod fetches from the backend at start. No size limit applies by default: the API host's
Ingress takes a body of any size, and `platform.backend.maxArtifactSize` (for example `500MB`) makes the backend
refuse a larger artifact before storing it. Size the PersistentVolumeClaim for the artifacts too. A headless
install has no backend to fetch from, so it runs airlocks without code only.


## Verify

```bash
kubectl -n aisolate get spaces,airlocks
helm test aisolate --namespace aisolate
```

`helm test` proves from inside the cluster that an airlock pod and an MCP server are unreachable except through
the ingress.
