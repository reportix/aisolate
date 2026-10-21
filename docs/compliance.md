# Compliance map

Where AIsolate's controls sit against the frameworks supervisors cite: OWASP's Top 10 for LLM applications,
the EU AI Act, DORA and the NIST AI RMF. Each row names the control, where it is enforced and what it leaves
open. A control *maps to* an item; nothing here is a certification, and the frameworks bind the organisation
that runs AIsolate, not the component. What a control leaves open is also in
[Residual risks](security.md#residual-risks).

## The controls

| | Control | Enforced in | Documented in |
|---|---|---|---|
| C1 | One token, validated at every hop, never exchanged; admission by the space's and the airlock's user lists | gates 1, 2, 4 and 5 | [The gates](security.md#the-gates), [Identity](security.md#identity) |
| C2 | The model is never an enforcement point: it receives text and tool schemas, nothing about the user, and only the airlock's tools are callable, at most three calls per exchange | gate 3 | [The gates](security.md#the-gates) |
| C3 | Rules compiled into one parameterised SELECT at the data boundary: row filters, hidden and masked columns, `min_group_size`; model text never enters the SQL | gate 5 | [Rules become SQL](security.md#rules-become-sql) |
| C4 | Deny is silent: an excluded row looks like a missing row, no answer reads "access denied" | gates 5 and 6 | [Deny is silent](security.md#deny-is-silent) |
| C5 | Figures are written in, never typed: every figure a reference to a cell, marked sourced, computed, unsourced or mismatch; "I don't know" when every evidence is empty | gates 6 and 7 | [Verification](verification.md) |
| C6 | One pod pair per airlock inside a default-deny namespace; images pinned by digest; the airlock's code fetched at start and checked against the digest its document names; read-only root filesystem, non-root, no capabilities, no ServiceAccount token | the controller, the chart, the CNI | [Pod isolation](security.md#pod-isolation), [Where an airlock's code comes from](security.md#where-an-airlocks-code-comes-from) |
| C7 | Egress by exact name only: a pod reaches its model, its data sources, the identity provider and the trace collector, and nothing else; every other host goes through the egress proxy or not at all | the controller's NetworkPolicies, the egress proxy | [Network policies](security.md#network-policies) |
| C8 | One audit line per exchange and per refusal: who asked what of which airlock, the tools and their arguments, row counts and hashes, the figures' provenance; stored append-only, chained per airlock, kept seven years by default, exported as XBRL against a published taxonomy | the airlock pod, the backend's database | [Audit](audit.md) |
| C9 | Traces and logs hold names and counts, never a question, answer, passage, row, SQL statement, token, e-mail or role | the pods | [What never goes into a span](observability.md#what-never-goes-into-a-span) |
| C10 | Credentials live in Secrets the backend may create and delete but never read; no password sits in a document, a response or a log line | the backend's Role, the controller | [Credentials](security.md#credentials) |
| C11 | Context is the developer's code, pinned by digest: passages and memory come from handlers the project ships, running as the user with the data source's role | the airlock pod | [Context preparators](security.md#context-preparators) |

## OWASP Top 10 for LLM applications (2025)

| Item | What holds | Left open |
|---|---|---|
| LLM01 Prompt injection | An instruction inside a passage or a row can steer the model, not the platform: a tool it calls returns what the rules let the asking user see (C3, C4); a tool the airlock does not list resolves to a stub that says it is unavailable (C2); only text leaves the airlock, to the browser and to the model's host (C7). Passages travel in a context message the preamble calls data, never in the system message | Wrong or misleading advice built on a poisoned passage: `AnswerVerifier` handlers annotate, they do not block. AIsolate does not detect or flag an injected passage |
| LLM02 Sensitive information disclosure | Hidden and masked columns are not queryable and a masked value never becomes a figure (C3, C5); nothing about the user is in the prompt (C2); rows never reach the browser; spans and logs carry no content (C9); the audit line holds provenance, never rows (C8) | The question and the answer are on the audit line, which makes the trail as sensitive as the conversations. A context preparator's passages reach every user the airlock admits, as far as its own predicates allow (C11). A model outside the network sees the prompt by design |
| LLM03 Supply chain | Images are deployed by digest, the chart refusing a reference without one; each airlock's code is hashed at start against the digest its document names (C6); every dependency carries a permissive licence | Images and artifacts are not signed; no software bill of materials ships with a release |
| LLM04 Data and model poisoning | AIsolate trains nothing and keeps no corpus of its own: passages come from the organisation's tables, read through the handler's role | Whoever writes the documents table writes the model's context: restricting that is the data owner's ([Context preparators](security.md#context-preparators)) |
| LLM05 Improper output handling | The answer is text; every figure in it is written in from a cell the user holds (C5); the browser receives the evidence event without the rows, the model's tool calls as events, and a provenance for every figure | The narrative around the figures is the model's, shown as written ([What is proven, and what is not](verification.md#what-is-proven-and-what-is-not)) |
| LLM06 Excessive agency | The model's reach is the airlock's tool list, three calls per exchange, each a read on a read-only role with a statement timeout and a row cap (C2, C3); the airlock pod has no write endpoint and reaches no host but the ones mapped for it (C7) | The airlock's own handlers hold a plain `DataSource` on the data sources the airlock resolves, outside the rules; the pod pair is the boundary, not the class loader |
| LLM07 System prompt leakage | The system message holds the platform preamble and the airlock's prompt, no credential, no identity, no rule (C2, C10); the rules are compiled in the MCP server and never shown to the model | A prompt the developer writes may say anything; AIsolate does not inspect it |
| LLM08 Vector and embedding weaknesses | Embeddings go through an LLM of the airlock's catalog, inside the network in the demo; the templates filter passages in the `WHERE` clause before `ORDER BY` and `LIMIT`, so a restricted passage is not revealed by its absence | The predicates are the handler author's invariant, not the platform's; a confidential corpus needs an embedding model inside the network |
| LLM09 Misinformation | Every figure is sourced, computed, unsourced or mismatch, and a reader sees which (C5); an exchange with no evidence answers "I don't know" (C4); verifiers add domain checks | Numbers in words, words of a text column nobody marked as a fact, and the narrative are not checked; a flag never blocks an answer |
| LLM10 Unbounded consumption | A statement gives up after 12 seconds; reads are capped at 100 rows and by the plugin; three tool calls per exchange; pods run with limits under the restricted Pod Security Standard (C3, C6) | Model cost is the model host's to meter; the audit line records tokens and duration per exchange |

## EU AI Act (Regulation (EU) 2024/1689)

The obligations fall on the provider and the deployer of a high-risk AI system. AIsolate is a component; the
rows say which records and controls it gives them.

| Article | What holds | Left open |
|---|---|---|
| Art. 12 Record-keeping: automatic logging of events over the system's lifetime | One line per exchange and per refusal, written by the pod before the answer leaves, kept append-only and chained, the current month plus 84 months by default (C8); the stdout copy for a log pipeline; `GET /v1/audit/verify` names the first altered or removed line | Changes an administrator makes to an LLM, a data source, a user list or a rule set are not on the trail; a database superuser who rewrites the chain's tail leaves a chain that verifies unless the head is kept outside AIsolate ([What the trail proves, and what it does not](security.md#what-the-trail-proves-and-what-it-does-not)) |
| Art. 14 Human oversight: the people in charge can understand, monitor and override the output | Every figure carries its provenance and the evidence it names, with the tool call and its arguments; unsourced and mismatched figures are flagged in the answer the person reads; verifiers' verdicts are shown and recorded; the Audit page reads every exchange back (C5, C8) | AIsolate blocks nothing on a flag: what a person does with a flagged answer is the organisation's process |
| Art. 15 Accuracy, robustness and cybersecurity | The isolation, identity and data-boundary controls (C1, C3, C6, C7); the golden set and the conformance suite that both runtimes must pass ([The golden set](verification.md#the-golden-set)) | No independent test of the isolation has been published; the residual risks name what a beaten pod still reaches |
| Art. 26(6) Deployers keep the automatically generated logs for at least six months | The default retention is seven years, set per install (`platform.audit.retentionMonths`); a dropped month is the only way a line leaves (C8) | A headless install has no backend: there the trail is the pods' stdout, kept by the log pipeline or not at all |

## DORA (Regulation (EU) 2022/2554)

| Article | What holds | Left open |
|---|---|---|
| Art. 9 Protection and prevention | Network segmentation per airlock with default deny (C6, C7); least privilege: read-only data source roles, a backend that cannot read Secrets, no ServiceAccount token in a pod (C3, C10); identity validated at every hop (C1) | Both hops inside the cluster are plain HTTP: TLS there is the organisation's service mesh or certificates |
| Art. 10 Detection | The audit line for every exchange and refusal, with reason, tool errors and verdicts (C8); Prometheus metrics and OTLP traces that count without content (C9); the egress proxy logs every connection | No alerting of AIsolate's own: the SIEM and the metrics pipeline are the organisation's |
| Art. 12 Backup, restoration and recovery | The trail sits on one PersistentVolumeClaim that outlives the pod; a dropped month is the only deletion; the JSON export carries each line's hashes | Backing that database up, and keeping the chain's head outside AIsolate, is the operator's |
| Art. 28 ICT third-party risk | The model is the organisation's choice by id, inside or outside the network; a host the admin has not mapped is unreachable, and an airlock that names one does not start (C7); its API key stays in a Secret (C10) | A model outside the network sees the prompt, the passages and the tool results by design; the provider's terms are the organisation's to assess |

## NIST AI RMF 1.0

| Function | What holds | Left open |
|---|---|---|
| Govern | Two realm roles (`aisolate-admin`, `aisolate-auditor`) and per-space Developer and User roles; the three documents are the interface, so a space, an airlock and its rules live in a repository and change by review; a disclosure channel in `SECURITY.md` | The policies that decide who may ask what are the organisation's |
| Map | Each airlock names its users, its callers, its tools, its data sources and its model by id; a rule set carries a `classification` | The classification is recorded, not enforced; a data-classification label on a data source does not exist |
| Measure | The verification labels per figure and their counts on every audit line; the golden set; the conformance suite both runtimes pass; metrics per space | No measure of the narrative's accuracy; no benchmark of the model itself, which is the organisation's |
| Manage | Deny is silent and fail-closed everywhere: an invalid airlock runs nothing, a pod that cannot keep its audit line answers no question, an unknown tool is a stub; the residual risks are listed with what is planned | The items marked planned in [Residual risks](security.md#residual-risks) |

## Read next

- [Security](security.md): one request end to end, the gates, the residual risks.
- [Audit](audit.md): the trail, its storage, the taxonomy and the export.
- [Verification](verification.md): what is proven about a figure, and what is not.
