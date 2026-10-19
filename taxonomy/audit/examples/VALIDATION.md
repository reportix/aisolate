# Validation with Arelle

Arelle 2.46.1 (`pip install arelle-release==2.46.1`, Apache-2.0) in a throwaway virtual environment, never a
dependency of AIsolate or its CI. Run from `taxonomy/audit` on 2026-10-21, on the AIsolate Audit taxonomy, version
1.0 (2026-10-26), with `--internetConnectivity offline` (the XBRL standard schemas come from Arelle's cache, the
taxonomy from the package) and `--logFormat "[%(levelname)s] [%(messageCode)s] %(message)s"`; DEBUG lines are left
out.

| Run | Result |
|---|---|
| The package, its entry point, the formula compiled | no errors, no warnings |
| `acme`, `halvard-finch`, `aldermoor` | no errors; RefusalCallsNoTool 1 satisfied, 0 not satisfied (one refusal each) |
| `broken` | exactly one error: RefusalCallsNoTool, 0 satisfied, 1 not satisfied |

The three stage demos' `audit.log` are the airlocks' audit events of 2026-10-19 on the local cluster, the demo
cast's questions after `scripts/local-cluster.sh test` passed (`../deployment`), each space's lines fetched from
`GET /v1/audit/events` as `auditor`, as the pods wrote them. Each `report.xbrl` is the report of its `audit.log`
for the period 2026-10-19/2026-10-19, written by the backend's report writer: `AuditReportExamplesTest`
(`libs/audit-report`) fails when one differs, and writes it again when it is deleted. The entities are the
invented LEIs `529900ACMEFINGROUP15`, `529900HALVARDFINCH06` and `529900ALDERMOORHSP32`. `broken` is
`halvard-finch` with one context and its facts added by hand: a tool call for its refused exchange.

```
$ arelleCmdLine --file aisolate-audit-2026-10-26.zip --validate
[INFO] [info] loaded in 0.01 secs at 2026-10-21T22:00:12
[INFO] [info] validated in 0.02 secs

$ arelleCmdLine --packages aisolate-audit-2026-10-26.zip --file https://aisolate.ai/xbrl/audit/2026-10-26/aisolate-audit-2026-10-26.xsd --validate --formula validate
[INFO] [info] Activation of package AIsolate Audit successful.
[INFO] [info] loaded in 0.01 secs at 2026-10-21T22:00:12
[INFO] [info] validated in 0.02 secs
[INFO] [info] formula validation only in 0.01 secs

$ arelleCmdLine --packages aisolate-audit-2026-10-26.zip --file examples/acme/report.xbrl --validate --formula run --formulaAsserResultCounts
[INFO] [info] Activation of package AIsolate Audit successful.
[INFO] [info] loaded in 0.01 secs at 2026-10-21T22:00:13
[INFO] [info] validated in 0.02 secs
[INFO] [formula:trace] Value Assertion RefusalCallsNoTool evaluations : 1 satisfied, 0 not satisfied
[INFO] [info] formula validation and execution in 0.01 secs

$ arelleCmdLine --packages aisolate-audit-2026-10-26.zip --file examples/halvard-finch/report.xbrl --validate --formula run --formulaAsserResultCounts
[INFO] [info] Activation of package AIsolate Audit successful.
[INFO] [info] loaded in 0.01 secs at 2026-10-21T22:00:13
[INFO] [info] validated in 0.02 secs
[INFO] [formula:trace] Value Assertion RefusalCallsNoTool evaluations : 1 satisfied, 0 not satisfied
[INFO] [info] formula validation and execution in 0.01 secs

$ arelleCmdLine --packages aisolate-audit-2026-10-26.zip --file examples/aldermoor/report.xbrl --validate --formula run --formulaAsserResultCounts
[INFO] [info] Activation of package AIsolate Audit successful.
[INFO] [info] loaded in 0.02 secs at 2026-10-21T22:00:13
[INFO] [info] validated in 0.02 secs
[INFO] [formula:trace] Value Assertion RefusalCallsNoTool evaluations : 1 satisfied, 0 not satisfied
[INFO] [info] formula validation and execution in 0.01 secs

$ arelleCmdLine --packages aisolate-audit-2026-10-26.zip --file examples/broken/report.xbrl --validate --formula run --formulaAsserResultCounts
[INFO] [info] Activation of package AIsolate Audit successful.
[INFO] [info] loaded in 0.01 secs at 2026-10-21T22:00:13
[INFO] [info] validated in 0.02 secs
[ERROR] [message:RefusalCallsNoTool] A refused question calls no tool: exchange cb1f62d93dbaf72b7477878bbcc4df87 was refused but reports 1 tool call(s).
[INFO] [formula:trace] Value Assertion RefusalCallsNoTool evaluations : 0 satisfied, 1 not satisfied
[INFO] [info] formula validation and execution in 0.01 secs
```
