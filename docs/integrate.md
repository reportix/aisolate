# Call an airlock from your workflow

A workflow tool such as n8n or KNIME, or a script, asks an airlock the way a chat does: it signs in, posts one
question and reads the answer. Nothing else is installed on either side. This page is the contract the ready-made
integrations in the [`aisolate-integrations`](https://github.com/reportix/aisolate-integrations) repository rely
on: an n8n community node (also an AI Agent tool) and an n8n workflow, a KNIME workflow and a KNIME extension whose
AIsolate Connector, Airlock Selector and Airlock Prompter nodes list the airlocks a client may ask.

## The JSON answer

The chat endpoint streams server-sent events unless the caller asks for JSON. A tool that cannot read a stream
sends `Accept: application/json` and gets the whole exchange as one document, under the same token, the same
admission and the same rules:

```bash
curl -s -X POST "$AISOLATE/v1/spaces/acme/airlocks/hr/chat" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  -d '{"message": "What is my gross salary?"}'
```

| Field | Meaning |
|---|---|
| `segments`, `figures`, `tables`, `quotes` | The answer in pieces and where each figure, table and quote comes from: `status` `sourced`, `computed`, `unsourced` (a number or a fact value the model typed) or `mismatch`, with a `reason` ([API](api.md#the-chat-endpoint), [Verification](verification.md)) |
| `provenance` | `status`: `all_sourced`, `has_unsourced`, `has_mismatch` or `no_figures`, and the four counts. A workflow that must not act on an unchecked number branches on it |

The header must be exactly `Accept: application/json` (or `application/*`): an `Accept` that also lists `*/*` or
`text/event-stream`, as many HTTP clients send by default, gets the stream, so set it explicitly in the tool.

The request body is `{"message": …}`, at most 4,000 characters. An exchange that cannot finish is a problem
document, not a partial answer: 503 when the model server is not available, 500 otherwise. An exchange runs at
most `AISOLATE_EXCHANGE_MAX_DURATION` (eight minutes by default), so give the HTTP step a longer timeout than
that. The other statuses are the stream's: [Errors](api.md#errors).

## Sign in

The workflow signs in with the identity provider's password grant and sends the access token on the call. With
Keycloak, realm `acme` (the application users' realm), a confidential client `etl` and the user `nora@example.com`. The demo realm
(`docker/keycloak/realm-acme.json`) has no `etl` client: create it there as below, or use a client of your own:

```bash
TOKEN=$(curl -s -X POST "$KEYCLOAK/realms/acme/protocol/openid-connect/token" \
  -d grant_type=password \
  -d client_id=etl -d client_secret="$ETL_CLIENT_SECRET" \
  -d username=nora@example.com -d password="$NORA_PASSWORD" \
  -d 'scope=openid profile email' | jq -r .access_token)
```

Three things must hold for the call to be admitted:

- the client has direct access grants enabled and carries the `aisolate` scope, which puts `aisolate` in the
  token's audience;
- the token's roles hold one of the space's or the airlock's own `access.roles`;
- the token's `azp`, here `etl`, is in the airlock's `access.callers`, or the callers are `*`.

A workflow that signs in as itself, with the client-credentials grant, has no user and no e-mail. Its token is
admitted when its `azp` is exactly one of the airlock's `access.clients`; the callers are not consulted
([In production](#in-production)).

Otherwise the airlock answers 401 (no valid token) or 403 (user or client not admitted), and the reason is in the
pod's log. Sign in once per run: an access token lives for minutes.

## The two templates

Both do the same four steps: take the parameters, sign in, ask, and lay the evidence out as a table.

| | n8n | KNIME |
|---|---|---|
| In the `aisolate-integrations` repository | `n8n/aisolate-ask.json` | `knime/aisolate-ask.knwf` |
| Form | A workflow file, imported in the editor or with `n8n import:workflow` | A workflow file for KNIME Analytics Platform 5.12, imported into a space |
| Parameters | One Set node, `Parameters` | Two Credentials Configuration nodes (client, user), the token endpoint in OAuth2 Authenticator, the URL and question in POST Request |
| Output | One item per evidence entry, each carrying the answer and its provenance | One row: the answer, its provenance and the evidence as a list |

The parameters are the host of AIsolate, the host of the application users' identity provider and its realm, the client id and
secret, the user and password, the space, the airlock and the question. The hosts default to a local
cluster and the client secret and password to `change-me`; replace every default for your installation.
The `aisolate-integrations` repository's README has the steps for each tool.

## What the workflow sees

The answer document goes one step further and carries no rows at all. The workflow gets the answer text, the
evidence **ids**, the name of the tool and of the entity each one came from, and **how many** rows it held;
never the rows, columns or passages themselves. A downstream node that logs, mails or stores the answer cannot
leak data the answer does not already state.

Use `dont_know` and `provenance` as the workflow's own gates: both templates carry the status (`provenance`) and its
four counts on every item, so a workflow routes an answer that is `has_unsourced` or `has_mismatch` to a person
instead of writing it on. `verification.verified` speaks for the airlock's own verifiers only, and is `false`
without any.

## In production

The templates sign in as a person to keep a demonstration short. A workflow that runs unattended should not:

- **A confidential client with the client-credentials grant alone.** Register a client for the workflow tool
  (`etl`), with a secret kept in the tool's credential store (n8n credentials, KNIME's Credentials Configuration
  or a secrets manager), never in the workflow file. Switch off every other grant, so no person signs in
  through it. The workflow then asks as itself: no service user, no e-mail.
- **Listed in the airlock's `access.clients`.** Add the client id to the Airlock's `access.clients` in the
  project file. Its token, which carries no e-mail, is admitted on that `azp` alone; the callers and roles are
  not consulted.
- **Its roles on the client.** Give the client, in the identity provider, only the roles whose data the
  workflow's questions need (in Keycloak, the client's service-account roles). What it may read is then bounded
  by those roles' rules, not by a person's.
- **Callers narrowed.** Replace `*` in the airlock's `access.callers` with the chat clients people ask from. The
  workflow's client needs no place there.
- **Audited as the client.** Its exchanges are on the audit trail with no `user` and its client id as `client`;
  the PDF export and the XBRL `User` fact show the client id.
- Keep the question text in the workflow, not in a column it receives from outside, so a source system cannot
  steer what the model is asked.
