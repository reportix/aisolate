# Security policy

AIsolate's job is to keep a model from seeing or returning what the asking user may not see. Any way
around that is a vulnerability, whatever the component.

## What counts

- A row, column or document passage reaching the model or the user that the airlock's rules exclude.
- A token, a name or a role reaching a model.
- A token accepted that the identity provider did not issue for the `aisolate` audience, or one
  replayed after its lifetime.
- A tool running that the airlock does not declare, or SQL text written by the model reaching a
  database.
- A refusal that is not silent: "access denied" where the design says "I don't know".
- A secret or business data written to a log, a trace or AIsolate's own database, other than the
  airlock's audit line: it records each exchange's question and answer by design, for the
  organisation's own audit. Any other log line that
  carries a question, an answer, a row or a passage still counts.

## Reporting

Do not open a public issue. Report privately through a confidential issue in this project's issue tracker
(tick *Confidential*) or security@reportix.com. Include the commit or version, the airlock definition, the token claims and the request that show the
problem; a failing test is the best report. We confirm receipt, keep you informed while we fix it, and
credit you in the fix unless you prefer otherwise.

## Supported versions

`main` and the latest release.
