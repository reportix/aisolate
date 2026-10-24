# Observability

AIsolate sends traces to the collector the organisation already runs and serves metrics to its scraper, in the
open formats only: OTLP for traces, the Prometheus text format for metrics. Any backend that reads them works;
AIsolate keeps no trace store, ships no collector and needs no dashboard tool. We recommend, all Apache-2.0: the
[OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) in front of [Jaeger](https://www.jaegertracing.io)
for the traces, and [Prometheus](https://prometheus.io) for the metrics; the demo runs exactly that. The chart
carries an example dashboard for those who view their metrics in Grafana. The audit trail is in
[Security](security.md#audit-trail).

## Traces

The chart's one observability value, `platform.observability.otlpEndpoint`, turns them on. It is the
collector's OTLP/HTTP base URL, for example `http://otel-collector.monitoring.svc:4318`; the pods append `/v1/traces`.
Its host must be mapped in `platform.egress.hosts` or proxied: otherwise every space is `Invalid` with
`platform.observability.otlpEndpoint host <h> is not in platform.egress.hosts`. The controller renders it as
`MANAGEMENT_OTLP_TRACING_ENDPOINT` on every airlock pod and tool server, and each pod's NetworkPolicy reaches
that host. Without the value nothing leaves the pods: the spans still exist, so the audit line and the JSON
answer still carry a `trace_id`, but they go nowhere.

Every exchange is a trace: sampling is 1.0. A question costs one trace of a few dozen spans, and an airlock
answers questions one person types at a time.

### What one trace shows

An auditor's question to the `northbeck` airlock of the `auditor` template (21 spans):

```
http post /v1/spaces/{spaceId}/airlocks/{airlockId}/chat
└── exchange audit/northbeck                    aisolate.outcome, aisolate.client, aisolate.figures.sourced,
    │                                           .computed, .unsourced and .mismatch (counts, never a value)
    ├── admission                               aisolate.admitted, aisolate.reason on a refusal
    ├── Read minutes and policies
    │   │                                       one span per context preparator and memory recall: aisolate.handler,
    │   │                                       aisolate.stage=pre_llm, aisolate.passages, aisolate.data_source
    │   └── embedding nomic-embed-text          aisolate.llm("nomic").embed in a handler: aisolate.llm, gen_ai.request.model,
    │       │                                   gen_ai.usage.input_tokens
    │       └── http post                       to the model provider
    ├── Remember the conversation               SessionMemory's recall
    ├── spring_ai chat_client                   Spring AI's spans, as Spring AI names them
    │   └── tool _calling
    │       ├── call
    │       │   └── chat granite4.1:8b          one per model call: aisolate.llm (the main LLM's id), gen_ai.request.model,
    │       │       │                           gen_ai.usage.input_tokens, gen_ai.usage.output_tokens
    │       │       └── http post               to the model provider
    │       ├── execute_tool related_party_payments
    │       │   └── tool related_party_payments aisolate.tool, aisolate.rows
    │       │       └── http post /mcp          the tool server, from here on
    │       │           └── governed query related_party_payments
    │       │               │                   aisolate.data_source, aisolate.entity, aisolate.rows, aisolate.outcome
    │       │               └── sql ledger
    │       └── call
    │           └── chat granite4.1:8b
    │               └── http post
    ├── spring_ai chat_client                   only when the answer was not in the answer format: the final
    │   └── …                                   turn, constrained to its schema ([Verification](verification.md))
    │                                           then one span per verifier (aisolate.verdict), none in this template,
    │                                           and per memory remember: aisolate.handler, aisolate.stage=post_llm
    └── Remember the conversation               SessionMemory's remember
```

A refusal is three spans: the HTTP span (403), `exchange` and `admission`, both with status ERROR.

- A handler's span is named by its `@Description`, or else by its class; `aisolate.handler` is its class.
- Every AIsolate span carries `aisolate.airlock` and `aisolate.revision`; the airlock pod's
  also carry `aisolate.space`.
- `aisolate.outcome` on the `exchange` span is the audit line's: `answered`, `dont_know`, `refused` or `error`.
  On the tool server's span it is `rows`, `empty`, `denied` (no rule granted anything, the silent deny),
  `invalid` (arguments the tool refused) or `error`.
- The airlock pod sends the W3C `traceparent` of the `tool <name>` span with each call, so the tool server's
  spans join the same trace.
- A caller's own `traceparent`, `tracestate` and `baggage` are ignored at the airlock pod: every exchange starts
  its trace there, so a caller can neither choose the `trace_id` the audit line and the answer carry nor sample
  an exchange out of the trace store.
- The audit line's `trace_id` and the JSON answer's are the root's: the trace store opens the exchange's trace by
  it (in Jaeger, `/trace/<trace_id>`).

### What never goes into a span

No question, answer, prompt, completion, passage, row, figure's value, SQL statement, token, e-mail, user or
role: the audit line names the user and the trace. The `exchange` span counts the answer's figures by
provenance, never what they are. Spring Security makes no spans here: its spans list the caller's roles, and
in a small team a role names one person. A span that failed has status ERROR and `error.type`, the exception's
class, and no message: an exception's message may quote any of these. Spring AI's content observations stay off
(`spring.ai.chat.observations.log-prompt`, `log-completion`, their `chat.client` twins and
`spring.ai.tools.observations.include-content`, all `false`). The question and the answer are in the audit line
only.

### In the AIsolate UI

**Monitoring > Tracing** shows the traces without a trace viewer: the exchanges newest first, by space, airlock and
time, each with its outcome, duration, tokens, model calls and tool calls; one opens its trace page, the
question as its title, the steps as a tree (admission, each context preparator with its embedding, each model
call, each tool call down to the tool server's governed query and its SQL, the verifiers, the memory), the
selected step's attributes, and the steps on a time axis. The admin sees every space's, a developer the spaces
they develop; the auditor reads the **Audit** pages instead.

The list is the audit trail's, so who sees which trace is the audit's rule, and the question, the answer and a
tool call's arguments come from the audit line, never from a span. The steps come from the trace store, which the
backend reads in the cluster; the browser never calls it. The chart's `platform.tracing.queryUrl` names its query
API, Jaeger's (`http://jaeger.monitoring.svc:16686`) or any store that answers Jaeger's `GET /api/traces/{id}`
alike, and `platform.tracing.egress` the NetworkPolicy peer the backend reaches it at, that host and port only.
Unset, the AIsolate UI has no Monitoring and the trace endpoints are 404. A trace the store no longer holds
(Jaeger's in-memory store keeps a bounded number) still shows its audit line, and the page says the steps are
gone. The endpoints: [The trace endpoints](api.md#the-trace-endpoints).

**Monitoring > Metrics** draws the example dashboard's five panels without Grafana: exchanges and tokens per
minute by airlock, the p95 exchange duration, refusals by reason and tool calls by tool, over the last hour, day,
week or month, by space and airlock. The backend asks Prometheus in the cluster (`platform.metrics.queryUrl`, with
`platform.metrics.egress` its NetworkPolicy peer, that host and port only) the dashboard's queries and no other:
nothing the browser sends becomes PromQL but an id. A developer's are scoped to their spaces by the series'
`space` label, so it needs the scrape relabeling below; series without it reach the admin alone. Unset, there is
no Metrics tab. The endpoint: [The metrics endpoint](api.md#the-metrics-endpoint).

## Metrics

Every AIsolate pod (controller, backend, airlock pods, tool servers) serves actuator on a management port of its
own, 9464, which no Service and no Ingress exposes: `/actuator/prometheus` without a token, and the health
groups the kubelet probes. The pod's container port is named `management`. A scraper reaches it through one
NetworkPolicy rule on each of those pods: a pod labelled `aisolate.dev/scraper: "true"`, in any namespace, on
9464 alone. Label your Prometheus pods (or your agent's) with it; the API and chat ports stay closed to it, and
the databases, the AIsolate UI and the egress proxy have no such rule. The label is a pod's own: anyone who may
create a pod in the cluster may set it and read the series, which carry names and counts only. The backend's
policy admits any peer on its ports, 9464 included. The
backend also answers `/readyz` on its API port, which the AIsolate UI probes.

| Series | Labels | Counts |
|---|---|---|
| `aisolate_exchanges_total` | `airlock`, `outcome` | Requests to an airlock: `answered`, `dont_know`, `refused` or `error`, as the audit line says |
| `aisolate_refusals_total` | `airlock`, `reason` | Refusals with a 403: `role` or `client` |
| `aisolate_tool_calls_total` | `airlock`, `tool` | Tool calls the model made |
| `aisolate_exchange_duration_seconds` | `airlock` | Exchanges from admission to the last event, a histogram (`_bucket`) |
| `gen_ai_client_token_usage_total` | `airlock`, `gen_ai_token_type`, Spring AI's model labels | Tokens the model calls used: `input`, `output`, `total` |
| `aisolate_tool_queries_total` | `airlock`, `tool`, `outcome` | Tool server calls: `rows`, `empty`, `denied`, `invalid` or `error`; `airlock` is `none` when the call named no airlock the tool server serves |

The airlock pod counts from its audit lines, so the metrics and the trail agree. Every label comes from a
closed set: the names the documents give, the tools an airlock lists, the outcomes and reasons above. Spring AI's
tool series (`spring_ai_tool_seconds`) name a tool the model invents `unknown`. No
e-mail, question or answer is ever a label. The trace's own observations (`aisolate.trace.*`) make no meters.

The chart's `examples/grafana-dashboard.json` is an optional Grafana 11 dashboard over Prometheus: exchanges per
minute and tokens by airlock, refusals by reason, tool calls by tool and the p95 exchange duration, each by space
and airlock, with a Space and an Airlock picker. The series name the airlock, not the space: copy
the pod label `aisolate.dev/space` onto a `space` label when you scrape (Prometheus:
`source_labels: [__meta_kubernetes_pod_label_aisolate_dev_space]`, `target_label: space`). Without it the panels
still show every airlock, unsplit by space. The two counts over the dashboard's range also count a series that
began within it: a pod's counter appears at its first increment, which `increase()` alone misses. Import it and
pick the Prometheus data source; any other tool that queries Prometheus can draw the same series.
