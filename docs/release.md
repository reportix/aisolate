# Release to a headless install

A headless install runs no AIsolate UI and no backend. Its documents are the release: the admin writes them,
creates the Secrets they name, maps their hosts, validates them and applies them through the organisation's own review. The merge
request is the review, the apply is the release, the git history is the record. AIsolate stores no approval
state. The same controller renders the same pods as in an install with the AIsolate UI. The last section, for
maintainers, is [how AIsolate itself is released](#how-aisolate-itself-is-released).

## Write the documents

`<space>.yaml` in the organisation's deployment repository: the `Space`, `Airlock` and `AirlockVersion`
documents, with URLs and Secret names, never a credential ([Example documents](documents.md#example-documents)).
A version's code is not in them: the pods fetch it from
the backend at start ([Where an airlock's code comes from](security.md#where-an-airlocks-code-comes-from)), so a
headless install, which has no backend, runs versions without code only; one with code does not start.

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
[chart README](https://github.com/reportix/aisolate-chart/blob/main/chart/README.md).

## Validate the documents

```bash
docker run --rm -v "$PWD:/release:ro" docker-public.sonatype.reportix.com/aisolate/airlock:X.Y.Z validate /release
```

The command parses the directory, prints every problem and exits 1 or 0 before the application starts. It
skips documents of other API groups. The
rules are in [Validation](documents.md#validation).

## Apply

`kubectl apply -f <space>.yaml`, Argo CD or Flux.

## Check status

```bash
kubectl -n aisolate get spaces,airlocks,airlockversions
kubectl -n aisolate describe airlockversion hr-v1
kubectl -n aisolate logs deploy/acme-hr-v1-airlock
```

An `AirlockVersion` is `Pending` until both of its pods rolled out, then `Ready`, or `Invalid` with its
problems. `kubectl get airlockversions` shows each one's airlock, version name, state, ready airlock pods and
revision. An `Airlock` and a `Space` report whether their release is valid, and list each of their versions
the controller holds back (`AirlockVersion <name> is held back: …`); an `Airlock` without a version renders
nothing and is `Ready`.

## What the controller renders

Per `AirlockVersion`, everything owned by it, named as [Names, ids and revision](documents.md#names-ids-and-revision)
lists:

- A version with code fetches it from the backend by `jar.digest` and refuses to start on any other bytes or a
  failed fetch; without a backend (headless) it does not start. A version without code fetches nothing.
- A version gets an MCP server only when it has code and lists a tool in `tools`.
- Once per namespace, owned by every `Space` so they go with the last one: discovery, `aisolate-discovery`.
- With `platform.observability.otlpEndpoint` set, both pods get it as `MANAGEMENT_OTLP_TRACING_ENDPOINT` and
  send their traces there ([Observability](observability.md#traces)).

## What the controller checks

Each space is its own release: the `Space`, the `Airlock`s that name it and their `AirlockVersion`s. A problem
holds back its own scope and says why on the resource it names: an invalid `AirlockVersion` is held back while
its siblings run on, an invalid `Airlock` holds back its versions, and only a problem of the `Space` or of the
install's settings holds back the whole space. So removing a role from a `Space` reaches every valid version
even while another is broken. The problems:

- a problem the loader finds ([Validation](documents.md#validation));
- a data source a version resolves without its URL or its `secretRef`;
- a host, of an LLM URL or of a data source URL a version, its airlock or its space defines, neither mapped in
  `platform.egress.hosts` nor proxied. Such a host of a global entry is a problem on the `Global`'s status
  instead, and that entry is left out of every version's catalog, so one global typo stops no space; a version
  whose main LLM it is is held back;
- a `jar.digest` that is not `sha256:`;
- a `platform.observability.otlpEndpoint` whose host is neither mapped nor proxied;
- a Service name over 63 characters.

A held-back version fails closed: the controller deletes everything it owns, so it serves no one until it is
valid again.

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
([Audit trail](security.md#audit-trail), [Audit](audit.md)). Logs name handlers and count rows; they carry no
rows, no passages and no token.

## How AIsolate itself is released

AIsolate's version is `version=` in `gradle.properties`; every merge request raises it. Every merge to `main`
publishes the images to Reportix's internal registry as `<version>-<sha10>`, the version and the commit's first
ten hex digits. A Maintainer releases a merged commit by tagging it with the bare version, `X.Y.Z`, equal to
`version=`: the tag publishes the images as `docker-public.sonatype.reportix.com/aisolate/<app>:X.Y.Z`, `<app>`
being `backend`, `controller`, `airlock`, `tool-server`, `airlock-python`, `tool-server-python` or `keycloak`
(Keycloak with AIsolate's login themes). The UI and the chat are not published yet: they bake their deployment's
URLs in at build time, so a deployment builds them from `apps/frontend/Dockerfile` and `apps/chat/Dockerfile`.

The tag's pipeline also builds the Windows CLI installer from source on the Windows runner
(`cli-windows-installer`: a GraalVM native image, then NSIS) and the Linux CLI archive on the Linux runners
(`cli-linux-archive`: the native image and the licence in `aisolate-cli-linux-x64.tar.gz`), and creates the
tag's GitLab Release with `aisolate-cli-installer.exe`, `SHA256SUMS`, `aisolate-cli-linux-x64.tar.gz` and its
`.sha256` as asset links to those jobs' artifacts, which never expire on a tag.

### The release notes

The Release's page is `docs/releases/<version>.md`, written before the tag and merged with the last change of
the release: highlights, the downloads table, the images, what an upgrade means, the documentation links. The
`gitlab-release` job reads it as the Release's description; the GitHub mirror's release gets the same text
(`gh release create X.Y.Z --notes-file docs/releases/X.Y.Z.md …`). A tag without its file fails the job.

### Signing

The tag's Windows installer and the binary inside it are Authenticode-signed through Azure Trusted Signing with
the `sign` dotnet CLI, the way skye/platform/desktop signs its installers; the job fails on a tag when the
signing variables are missing, so a release is signed or not made. The six are instance-level variables of the
GitLab, shared with Skye: `TRUSTED_SIGNING_ENDPOINT`, `TRUSTED_SIGNING_ACCOUNT`, `TRUSTED_SIGNING_PROFILE`,
`AZURE_TENANT_ID`, `AZURE_CLIENT_ID` and `AZURE_CLIENT_SECRET`. Branch builds stay unsigned, except
`cli-windows-installer-signed-manual`, a signed build to try by hand. The Linux archive is not signed: nothing
on Linux checks a signature on a tarball, and the release serves the archive and its checksum over HTTPS.

### The macOS installer

No macOS runner exists, so a maintainer builds the `.pkg` on a Mac from the tag's checkout and attaches it to the
same release with its checksum:

```bash
git checkout X.Y.Z
GRAALVM_HOME=<GraalVM 25 home> ./gradlew :apps:cli:createMacosInstaller
cd apps/cli/build && shasum -a 256 aisolate-cli-installer.pkg > aisolate-cli-installer.pkg.sha256
glab release upload X.Y.Z aisolate-cli-installer.pkg aisolate-cli-installer.pkg.sha256
```

### The GitHub mirror

The mirrored release carries the same assets, published by a maintainer: download the six files from the GitLab
Release and `gh release create X.Y.Z --repo reportix/aisolate --title "AIsolate X.Y.Z" --notes-file docs/releases/X.Y.Z.md
aisolate-cli-installer.exe SHA256SUMS aisolate-cli-linux-x64.tar.gz aisolate-cli-linux-x64.tar.gz.sha256
aisolate-cli-installer.pkg aisolate-cli-installer.pkg.sha256`. The CLI guide links the mirror's latest release.

`./gradlew :apps:controller:syncCrds` writes the CRDs into `crds/` from the controller's resource classes; a
change to those classes commits the regenerated files with it. The chart is released on its own, with a version
of its own, in [`aisolate-chart`](https://github.com/reportix/aisolate-chart): its pin script sets the chart's
`appVersion` and the image digests of a release `X.Y.Z` and copies `crds/` from that tag.
