# Validation with an XBRL processor

The runs below validate the AIsolate Audit taxonomy, version 1.0 (2026-10-26), and its example reports. Any XBRL
processor that reads taxonomy packages and runs Formula 1.0 assertions makes them; none is a dependency of AIsolate
or its CI. They were made from `taxonomy/audit` on 2026-10-21, offline: the XBRL standard schemas came from the
processor's own cache, the taxonomy from the package.

Each run does what any XBRL processor must do:

1. Load the taxonomy package `aisolate-audit-2026-10-26.zip`, so every URL under
   `https://aisolate.ai/xbrl/audit/2026-10-26/` resolves from it and nothing is fetched.
2. Validate the package, its entry point or a report against the taxonomy.
3. Run the formula and report each assertion's result: `RefusalCallsNoTool`, satisfied or not, once per refused
   exchange.

| Run | Result |
|---|---|
| The package, its entry point, the formula compiled | no errors, no warnings |
| `acme`, `halvard-finch`, `aldermoor` | no errors; RefusalCallsNoTool 1 satisfied, 0 not satisfied (one refusal each) |
| `broken` | exactly one error: RefusalCallsNoTool, 0 satisfied, 1 not satisfied |

The three stage demos' `audit.log` are the airlocks' audit events of 2026-10-19 from an end-to-end run of a local
install, the demo cast's questions, each space's lines fetched from `GET /v1/audit/events` as `auditor`, as the pods
wrote them. Each `report.xbrl` is the report of its `audit.log`
for the period 2026-10-19/2026-10-19, written by the backend's report writer: `AuditReportExamplesTest`
(`libs/audit-report`) fails when one differs, and writes it again when it is deleted. The entities are the
invented LEIs `529900ACMEFINGROUP15`, `529900HALVARDFINCH06` and `529900ALDERMOORHSP32`. `broken` is
`halvard-finch` with one context and its facts added by hand: a tool call for its refused exchange.

The commands show the shape of each run; every processor names its options its own way.

```
$ <xbrl-processor> --validate aisolate-audit-2026-10-26.zip
no errors, no warnings

$ <xbrl-processor> --validate --formula --packages aisolate-audit-2026-10-26.zip https://aisolate.ai/xbrl/audit/2026-10-26/aisolate-audit-2026-10-26.xsd
no errors, no warnings

$ <xbrl-processor> --validate --formula --packages aisolate-audit-2026-10-26.zip examples/acme/report.xbrl
no errors
RefusalCallsNoTool evaluations : 1 satisfied, 0 not satisfied

$ <xbrl-processor> --validate --formula --packages aisolate-audit-2026-10-26.zip examples/halvard-finch/report.xbrl
no errors
RefusalCallsNoTool evaluations : 1 satisfied, 0 not satisfied

$ <xbrl-processor> --validate --formula --packages aisolate-audit-2026-10-26.zip examples/aldermoor/report.xbrl
no errors
RefusalCallsNoTool evaluations : 1 satisfied, 0 not satisfied

$ <xbrl-processor> --validate --formula --packages aisolate-audit-2026-10-26.zip examples/broken/report.xbrl
[ERROR] [message:RefusalCallsNoTool] A refused question calls no tool: exchange cb1f62d93dbaf72b7477878bbcc4df87 was refused but reports 1 tool call(s).
RefusalCallsNoTool evaluations : 0 satisfied, 1 not satisfied
```
