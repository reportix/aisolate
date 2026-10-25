# Design decisions

Why AIsolate is built the way it is: each decision.
Where each is enforced is
[Security](security.md).

## The model is never an enforcement point

**Decision.** It receives text, tool schemas and the rows the asking user may see, and
returns text. It never receives a token, a name or a role, and it executes nothing: a tool call is a request the
airlock pod and the MCP server judge with the user's token, not an action the model performs.

## Permissive dependencies only

**Decision.** Apache, MIT, BSD, PSF, ISC and OFL only; nothing GPL, LGPL, AGPL or MPL, however convenient. Hence
pg8000 rather than psycopg, httpx2 rather than requests, a twenty-line OTLP exporter rather than the one that
pulls `requests`, a licence check in the Python image build, and no dependency for what a few lines do.

## Two identity domains: operators and application users

**Decision.** The pods (airlock, MCP server, discovery) trust `platform.identity`, the organisation's identity provider, where the application users are. The backend, and through it the UI and the CLI, trust `platform.identity.operators`, which defaults to the same provider, so a one-realm install is unchanged. The release host's discovery document is the pods', the application users'; the backend host's is the operators'. In UI mode the release host's `/.well-known/` answers 503 until the first space exists: the `Space`s own the discovery pods.

## Read next

- [Architecture](architecture.md): the modules and the pods these decisions shaped.
- [Security](security.md): one request end to end, the gates, the residual risks.
- [Concepts](concepts.md): the vocabulary.
