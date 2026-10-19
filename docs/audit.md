# Audit

What the airlocks did, kept twice: the audit trail, one line per exchange, which AIsolate keeps itself, and the
audit report, an XBRL report built from those lines, of one exchange or of every line a search finds.

## The audit trail is part of the platform

**The audit record** is one JSON line per exchange and per refused request, written by the airlock pod. It says
who asked what of which airlock, what came of it and what it cost; it holds provenance, never rows.

AIsolate keeps these lines in its own database, the backend's, and reads them back in the AIsolate UI (**Audit**)
and through the API. Nothing depends on a log pipeline: tracing and monitoring are optional views
([Observability](observability.md)); the audit trail is not. An install without the AIsolate UI (`headless`) has
no backend, so there the stdout line is the only copy.

### How a line reaches the backend

1. The airlock pod writes the line to its buffer: one file per line on an `emptyDir` of the pod
   (`/var/lib/aisolate/audit`, at most 64 MB of lines), so a restarted container keeps what was not sent.
2. One sender hands the oldest line to the backend's ingest, `POST /v1/audit/events` on its ingest port 8085,
   with the airlock's **ingest token**, and deletes it once the backend took it (201, or 200 for a line it
   already holds). While the backend is away it retries with backoff, from half a second to thirty, in order:
   no line overtakes another. A line the backend refuses as no audit line (400) is dropped and logged, so it
   cannot hold back every line after it.
3. **Fail closed.** When the buffer is full, the pod refuses new questions with 503 `audit unavailable` until it
   drains: no exchange runs that the trail could not keep. An exchange already admitted is always kept. Chart
   value `platform.audit.failClosed` (default `true`); `false` keeps answering and drops the lines that do not
   fit, counted in `aisolate.audit.dropped`. `aisolate.audit.buffered.bytes` is the buffer's fill.
4. A pod that stops (a redeploy replaces it) tries for ten seconds to hand over what is left; what it could not
   is logged by its size, as the `emptyDir` goes with the pod.

**The ingest token.** The controller renders one Secret per airlock, `<space>-<airlock>-audit`, and
mounts it into that airlock's pod alone; its MCP server and the discovery pods get none. The token is
HMAC-SHA256 of `<space>/<airlock>` under the install's ingest key, a Secret the chart generates
(`audit-ingest`) and gives the controller and the backend only. The backend takes a line only with the token of
the airlock the line names (403 otherwise), so one airlock's pod cannot write another's lines, and keeps no table
of tokens. The pod's NetworkPolicy reaches the backend's ingest port and no other port of it; the ingest port
answers nothing but the ingest, and the API port never answers the ingest.

**SIEM forwarding (optional).** Each line is also printed to the pod's stdout on the logger `aisolate.audit`, one
JSON object per line with nothing in front of it, for a log collector to ship to a SIEM. Chart value
`platform.audit.stdout` (default `true`); `false` keeps the lines in AIsolate alone. Without a backend the line
is always printed.

### Storage

The table `audit_events` in the backend's PostgreSQL: the line itself (`body`, its JSON with sorted keys) and,
read from it, the columns the filters use (time, space, airlock, revision, event, trace id, user,
client, outcome, reason, question, answer, figure counts, tokens, duration), and its link in the chain.

- **Append-only.** The backend writes and reads it as the database role `aisolate_audit_writer`, which may INSERT
  and SELECT and nothing else; a trigger refuses UPDATE, DELETE and TRUNCATE whoever asks, the table's owner
  included.
- **Tamper-evident.** Per airlock, every line is chained to the one before it:
  `hash = sha256(prev_hash || body)`, the first line's `prev_hash` 64 zeros. Lines of all airlocks and replicas
  of an airlock are chained one at a time. `GET /v1/audit/verify?space=&airlock=` walks the chain again and names
  the first line that fails: `altered` (its line or a column no longer gives its hash) or `removed` (it no
  longer follows the line before it).
- A retry whose answer was lost sends the same line again; the backend finds it by its digest and answers with
  the stored id, so a line is stored once.

### Retention

The table is partitioned by month (UTC) of receipt. The backend keeps the current month and
`platform.audit.retentionMonths` months before it (default 84, seven years) and drops older months whole, at
start and every six hours; it also creates this month's and next month's partition ahead of time. Dropping a
month is the only way a line leaves, and it runs as the table's owner, not as the writer. After a drop, the
oldest month kept anchors the chain: verify starts at its first line.

The trail is only as durable as the backend's database: the chart keeps it on the PersistentVolumeClaim `db`
(`persistence.postgres.storage`), which outlives the pod and goes with `helm uninstall`. Back that database up.
A headless install has no backend: there the trail is the pods' stdout alone, kept by your log pipeline or not
at all.

### Reading it

| Who | What |
|---|---|
| `aisolate-auditor` (realm role) | The **Audit** page of the AIsolate UI and the audit endpoints, every space's, nothing else |
| `aisolate-admin` (realm role) | The same, beside everything else the admin may do |
| A developer of a space | The same for the spaces they develop: the list holds only their events, another space's event or chain is 403 |

The **Audit** page lists the lines newest first (time, space, airlock, user, client, outcome, figures by
provenance, tokens), with filters; one opens the exchange: the question, the answer with each figure's
provenance, the tools with their arguments and row counts, the verdicts, the tokens, its trace (Monitoring's trace page when
the install has a trace store, else a configured trace viewer), and whether its airlock's chain is intact. **Export as**, next to **View trace**, downloads that exchange alone: a PDF to read, its line as JSON, or its XBRL report ([Export](#export)).

## The taxonomy

The AIsolate Audit taxonomy lives in [`taxonomy/audit`](../taxonomy/audit/README.md): namespace
`https://aisolate.ai/xbrl/audit/2026-10-26`, prefix `aisolate`, one entry point, published by AIsolate as a
Taxonomy Package (`aisolate-audit-2026-10-26.zip`).

A report is one XBRL 2.1 instance in XML (xBRL-XML), a single `.xbrl` file, of one organisation (the entity) and
one period. Its facts form three tables, the taxonomy's three hypercubes:

- **Exchanges**, one row per question put to an airlock: the space, the airlock, the time, the
  user, the question, the answer (absent unless answered), the outcome (`answered`, `dont_know`, `refused`,
  `error`), the model, its input and output tokens and the duration.
- **Tool calls**, one row per tool call of an exchange: the tool, the data source and the rows returned.
- **Verifiers**, one row per verifier run on an exchange's answer: its name and verdict (`pass`, `warn`,
  `fail`).

Each exchange is a context of its own: the typed dimension `aisolate:ExchangeAxis`, its member
`aisolate:ExchangeId` the trace id. A tool call and a verifier run add their number within the exchange
(`aisolate:ToolCallAxis`, `aisolate:VerifierAxis`, from 1). The facts in those contexts are the concepts:
`aisolate:User`, `aisolate:Question`, `aisolate:Answer`, `aisolate:Outcome` and the rest. Everything
else the audit trail holds (the client, the refusal reason, the evidence, the figure counts, a tool call's
arguments and hashes) stays in the trail, read in the **Audit** page and through the API; the report has no fact
for it.

**The one check.** A refused question calls no tool: an exchange whose outcome is `refused` has no tool call. A
report that breaks it is not a valid AIsolate Audit report; an XBRL processor checks it.

## Export

**Export as** on an audit event's page downloads that exchange alone, through
`GET /v1/audit/events/{id}/export?format=pdf|json|xbrl`: `aisolate-audit-event-<id>.pdf`, `.json` or `.xbrl`.
The report of every line a search finds over a period comes from the API alone, `GET /v1/audit/export` with the
list's `q`, `from`, `to` and `space`: `aisolate-audit-<UTC date>.xbrl`. Both have the access of the audit
endpoints.

The `.xbrl` file is plain XML text, not an archive: open it in a text editor or a browser, or load it in an XBRL
processor with the taxonomy package ([The taxonomy](#the-taxonomy)).

The report's entity is `AISOLATE_AUDIT_ENTITY`, an LEI or `<scheme>:<id>`, the install itself when it is empty. Its period is whole UTC days: from `from`'s day (the oldest line's without
one) to `to`'s (today without one).

An audit line the report cannot use (no space, airlock or time; an unknown outcome or verdict; a negative
or fractional count) is left out. An exchange is reported once, from its first line.

The report holds the question and the answer as they were asked and given: a hospital's report holds what its
doctors asked about patients and what they were told, so it stays inside the organisation, as the audit trail
does.

How the lines become facts: `trace_id` is the exchange's `aisolate:ExchangeId` (the start time when a line has
none), `space` and `airlock` the Space and Airlock, `ts` the Exchange time, `model` the
Model (`provider/name`) and its token counts, `duration_ms` the Duration, `tools` and `verifiers` the numbered tool
calls and verifier runs (a verdict in lower case). An answer that is empty or begins with "I don't know" (after any leading emphasis or quote marks) is
`dont_know`, as the airlock logs it, even on a line an older airlock wrote as `answered`. A refusal reports its
space, airlock, time, user, question, outcome and duration, never an answer, a model, a tool or a
verifier. Token counts a provider did not report are left out, not zero.

## Validating the report

Any XBRL processor that runs formula assertions validates a report. Register the taxonomy package,
`taxonomy/audit/aisolate-audit-2026-10-26.zip`, so the taxonomy's URLs resolve from it and nothing is fetched
from aisolate.ai; open the `.xbrl` file; validate it with the formulas run. A valid report logs no error. A broken
check logs one error per offending exchange:

```
[message:RefusalCallsNoTool] A refused question calls no tool: exchange cb1f62d93dbaf72b7477878bbcc4df87 was refused but reports 1 tool call(s).
```

The three stage demos' reports and a broken one, with every run and its result:
[`taxonomy/audit/examples`](../taxonomy/audit/examples/VALIDATION.md).
