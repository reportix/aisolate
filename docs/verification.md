# Verification

How AIsolate's grounding shows where every figure of an answer comes from, and what that proves. The model never
types a value from the data: it places a reference, and AIsolate writes the value in from the evidence and checks
the reference. The checks are deterministic: no model is asked, and the same answer over the same evidence always
comes out the same. A project's own checks of the finished answer are its [verifiers](#verifiers).

## Grounded answers

The model's final answer is one JSON object in AIsolate's answer format, the same for every airlock:
text with placeholders, and for each placeholder what it refers to.

```json
{"text": "Your gross salary is {f1}. You have {f2} left this year; the notice period is {q1}.",
 "figures": [
   {"id": "f1", "cell": {"evidence": "ev-2", "row": "r1", "column": "gross_salary"},
    "about": {"subject": "self"}},
   {"id": "f2", "computed": {"op": "subtract", "of": [
      {"evidence": "ev-3", "row": "r5", "column": "entitlement_days"},
      {"evidence": "ev-3", "row": "r5", "column": "taken_days"}]},
    "about": {"subject": "self", "period": "2026"}}],
 "quotes": [{"id": "q1", "passage": {"evidence": "ev-1", "index": 2},
             "text": "six months to the end of a calendar quarter"}],
 "dont_know": false}
```

The user gets "Your gross salary is 235,000 EUR. You have 18 days left this year; the notice period is "six
months to the end of a calendar quarter"." with each figure's provenance beside it: the evidence, the entity, the column,
the row's key and its labels, "Nora Varga · EUR · hr.compensation".

A placeholder refers to one of:

| Reference | What it is | AIsolate writes in |
|---|---|---|
| `cell` | One cell: `evidence`, `row`, `column` | The value, formatted by its type with its unit: `78000.00` as `78,000 EUR`; to the column's declared `decimals`, a group's average to two places (`119.25 mmHg`) |
| `computed` with `op` `add`, `subtract`, `multiply`, `divide`, `percent_change` (from the first cell to the second), `sum`, `average`, `min`, `max`, `count` and `of` | An operation over cells | The result, in exact decimals (to the cells' declared `decimals` in the text, else a division or an average to two places; ten in `value`), its unit derived from the cells' |
| `computed` with `op` `top_n` or `bottom_n`, `by` (`evidence`, `column`) and `rows` | The highest or lowest rows of one tool result, in rank order | The rows' names, in order |
| `computed` with `op` `max` or `min` and `by` | The highest or lowest value of a column of one tool result, and the row holding it (`rows` names it) | The value |
| `table` with `evidence`, `rows`, `columns` and an optional `rank` (`op` `top_n` or `bottom_n`, `by` a column) | Rows of one tool result | A markdown table of their cells |
| a quote (`quotes`) with `passage` (`evidence`, `index`) and `text` | Exact words of a document passage | The words, in quotation marks |

`about` is the model's own claim of what a figure is about: `subject` (whom, as the row names them, or `self` for
the asking user's own row), `period`, `concept` (the column, or the concept a row names) and `unit`, each
optional. It is checked against the row; a claim that disagrees is a mismatch.

The model sees every tool result's rows with a handle, `{"row": "r1", ...}`, numbered across the exchange (a
second tool result goes on at `r4`), and every passage under its evidence id and index, `[ev-1, passage 2]`. The
handle stays inside the airlock, mapped to the row.

## How the format is asked for

- The airlock appends the format's instruction and its JSON schema to every airlock's system prompt, after the
  airlock's own prompt: it is the platform's, not a template's.
- The model answers after its tool calls. An answer already in the format is used as it is.
- An answer not in the format is asked for once more: the final turn, without tools, constrained to the
  schema by the provider's structured output where it has one (Ollama's `format`, an OpenAI-compatible
  server's non-strict `json_schema`, through Spring AI's `outputSchema`), with the tool results repeated. An answer that still does not parse is asked for once more,
  with what was wrong ("it is not JSON", "text is missing"); the text of that error never quotes the answer.
- Still not in the format: the model's text is shown as it wrote it, and every number in it, and every value of a
  fact column, is unsourced.
- An answer without a number, from no tool rows (a greeting, an answer from documents alone), needs no format and
  no extra turn: there is nothing to ground.
- When the rules deny every row, the answer is exactly "I don't know" whatever the model wrote; a
  format answer with `dont_know` true is exactly "I don't know" too.

## The checks

Each figure gets one status:

| Status | When |
|---|---|
| `sourced` | A `cell` that resolves, and whose row agrees with every `about` claim |
| `computed` | An operation AIsolate evaluated over cells that resolve, a ranking the evidence confirms, a max or min |
| `unsourced` | Digits, or a value of a fact column, the model typed in the text, outside a placeholder, that the question does not hold |
| `mismatch` | A reference that does not resolve, or a claim the evidence contradicts |

A reference resolves when the evidence exists in this exchange and is a tool result, the row handle is one of
its rows, and the column is one of its columns. A mismatch carries a `reason` a reader understands:

- **No such reference**: "there is no evidence ev-9 in this exchange", "ev-1 is a document, not a tool result:
  quote it", "ev-2 has no row r9", "r5 is a row of ev-3, not of ev-2", "ev-3 (hr.leave) has no column
  gross_salary", "the answer places {f2} but gives no reference for it".
- **Masked or hidden**: a hidden column never reaches the airlock, so it has no column to cite; a masked one
  arrives as `****` and is refused: "column iban of ev-2 is masked: it cannot be cited". Its figure shows `…`,
  never the stars.
- **Whom**: "the row is about Eric Lindholm, not Leo Brandt". `self` on a row not marked `self`: "the row is not
  the asking user's own". Beside the asking user's own row, a figure from another row must name whom it is about:
  "the row is not the asking user's own, and the figure does not say whom it is about". A group's value (an
  aggregate) is about no one: "the row is the avg of a group (60-79), not about Julian".
- **When**: "the row is about 2025, not 2026". ISO periods compare by prefix (`2026` holds `2026-03-14`,
  `2026-04` does not); a period in words compares by its years.
- **What**: "the figure is target_bonus, not gross_salary"; where each row is one concept's figure (a reported
  figure's `concept`, `label`, `period`, `value_eur`), the claim is compared with the row's text too.
- **Unit**: "the figure is in GBP, not EUR". The words beside a placeholder may not change it: "the text calls it
  EUR, the row says GBP" (`{f1} EUR` on a GBP amount, `${f1}` on a EUR one), "the text scales it: thousand"
  (`{f1} thousand`, `{f1}k`), "the text makes it a percentage", "the text puts a sign before it" (`-{f1}`,
  `minus {f1}`). The figure's own unit beside it is dropped, so it is not written twice.
- **Computations**: operands must be numbers in one unit ("add mixes EUR and GBP"); a division by zero is a
  mismatch. `percent_change` is in `%`; `divide` keeps the numerator's unit over a unitless count.
- **Rankings and tables**: the ranked rows must be exactly the top or bottom n of the evidence by that column,
  in order; rows tied at the cut may be either. "the rows are out of order: Leo Brandt (r7) ranks before Nora
  Varga (r5)", "Tailspin Air (r7) belongs in the top 2 by contract_value_eur and is missing", "Fabrikam
  Logistics (r6) is not in the top 3", "a row is ranked twice". A column whose rows are in different units cannot
  be ranked ("gross_salary of ev-2 is in EUR and GBP"). A max or min names the row: "Nora Varga (r5) does not hold
  the highest remaining_days of ev-3; Leo Brandt (r7) does".
- **Quotes**: the words must be in the passage, whitespace aside: "these words are not in ev-1, passage 2". A
  quote that is not in the passage keeps its text, and its numbers and fact values are unsourced.

Typed digits: every token of the text holding a digit (`78,000`, `2026-09-01`, `INV-2026-0412`, `Q1`) is an
`unsourced` figure, `u1`, `u2`, ..., unless the question holds the same token, it is an evidence id (`ev-1`), or
it numbers a list item at the start of a line. Its `reason` names a cell holding the same value when one does
("typed in the text, not referenced; ev-4 r8 value holds the same value"): a hint for the reader, never a green.

Typed facts: a value of a fact column ([the labels](#the-labels)) of any tool result of the exchange, typed in the
text outside a placeholder, is an `unsourced` figure in the same sequence, shown as typed, unless the question holds
the same phrase. The value is matched as a whole phrase: in any case, with any run of spaces between its words, at
least three characters, and never inside a longer word or number (`insulin` is not in "insulinoma", `5 mg` is not in
"2.5 mg"); of two values the longer wins ("insulin glargine", not "insulin"). A verbatim quote is the passage's words
and is not scanned. The digits scan then runs over the text the facts leave, so `5 mg` typed from a `dose` column is
one unsourced figure, not a bare `5`. The `reason` names a cell holding the value ("typed in the text, not
referenced; ev-3 r4 drug holds the same value").

The answer's `provenance` counts the figures, a table counting once: `status` is `has_mismatch` if any is a
mismatch, else `has_unsourced` if any is unsourced, else `all_sourced`, and `no_figures` when there is none.
Quotes are listed apart, `quoted` or `mismatch`, and do not count.

## The labels

A figure's labels come from the entity its tool read, which the plugin declares with the SDK:

```java
new Entity("hr.compensation", "...", "employee_id",
        new Column("full_name", "e.full_name", Column.Type.TEXT),
        new Column("gross_salary", "c.gross_salary", Column.Type.NUMBER).withUnitFrom("currency"),
        new Column("currency", "c.currency", Column.Type.TEXT))
    .withSubjectName("full_name");

new Entity("hr.leave", "...", "employee_id",
        new Column("year", "l.year", Column.Type.NUMBER),
        new Column("remaining_days", "l.remaining_days", Column.Type.NUMBER).withUnit("days"))
    .withSubjectName("full_name")
    .withPeriod("year");
```

- `withSubjectName(column)`: the column naming whom a row is about, for a reader. `withSubject(column)` stays
  what it was, the id a minimum group size counts and the `self` mark compares; an id is no name, so an entity
  that names none labels no subject.
- `withPeriod(column)`: which period a row is about (a year, a period, a date).
- `Column.withUnit("EUR")` (or `ng/L`, `mmHg`, `%`, `days`), or `withUnitFrom(column)` when each row names its
  own (a currency column, a lab result's unit).
- `Column.asFact()` (Python `fact=True`, or `with_fact()`) on a text column whose values are facts of the row, such as
  a drug, a dose, a frequency or a diagnosis: the model must cite one, and a value of it typed in the text is
  unsourced (Typed facts, above). A number column takes no mark, since every number typed is checked, and refuses it.
  No text column is scanned unless marked: a status or category word ("overdue", "payment") is ordinary prose. The
  hospital marks `drug`, `dose` and `frequency` of its medications and `diagnosis` and `icd10` of its diagnoses; the
  other templates mark none.

The tool server sends them with every result as `labels`, only for the columns the user may see: a masked
column labels nothing, and a masked fact column is not scanned (its values arrive as stars). An aggregate's rows
are its groups: the grouping column keys them, `value` is in the aggregated column's fixed unit (a count in none),
`function` says it is about a group, and no column is a fact.

## What is proven, and what is not

Proven, for every figure marked `sourced` or `computed`:

- It comes from a cell of a tool result of this exchange, one the rules let the asking user read: a masked or
  hidden value can never be a figure, and an aggregate never bypasses a minimum group size, since AIsolate
  computes over cells the user already holds.
- It is written as that cell holds it, or as AIsolate computed it from such cells: no copied-wrong digit, no
  wrong scale, no wrong sign, no unit but the row's.
- Whom, which period, which concept and which unit the model said it is about agree with the row's labels.
- A ranking is the ranking of the evidence it names, and the provenance shows the tool call and the arguments
  its schema declares and its query applied (not an undeclared `sort`, not a `limit` the rows did not reach), so
  a reader sees the query's scope. The audit line keeps the arguments as sent.

Not proven:

- **The narrative.** Words around the figures ("increased", "the highest", "most of the team") are the model's,
  unverified, unless they are themselves a computed figure or a ranking. The text is shown as written.
- **Whether the evidence is the whole story.** A ranking holds within the rows the tool returned, and a tool
  returns at most 20 or 50; a filter the model chose narrows them. The call's arguments are in the provenance
  for that reason.
- **Which row the question meant**, when the figure claims nothing. The labels are shown so a reader sees it;
  only a claim (`about`) can be checked.
- **Numbers in words** ("seventy-eight thousand"), **words of a text column nobody marked as a fact**, and labels
  an entity does not declare: AIsolate checks the digits, the values of the fact columns and the labels it has.
- **What a quote means**: it is the passage's words, not a reading of them.
- **The arithmetic's choice**: AIsolate computes `subtract` exactly, but the model chose the operands and their
  order.

Unsourced figures never block an answer: they are flagged "Unsourced", the loud state, and red is only for a
mismatch.

## Verifiers

Grounding checks what a figure claims about itself and cannot know which row the question meant: with no claim, "the
last troponin" answered with the earlier reading is `sourced`, and so is Northwind's invoice for a question about
Fabrikam. Only the project knows what its questions expect, so an airlock's `AnswerVerifier` handlers judge that. They
run after the answer is grounded, on the grounded answer (each figure with its source's entity, column, row key,
subject, period and unit), the question and the evidence, and return PASS, WARN or FAIL with a sentence; their
verdicts are `verification` on the answer, apart from the figures. A verdict annotates and never blocks, and a PASS
says the rule found nothing wrong, not that the answer is right. To write one: [Write handlers](develop.md#write-handlers).

The templates ship these, one class per language, as reference code for a project's own questions. The title is the
handler's `@Description`, which the AIsolate UI shows in the airlock's graph.

- `LatestReadingVerifier`, hospital ("Check the latest reading"): a question for the last, latest, most recent or
  current reading is answered with the latest row of every series the answer cites (one patient's readings of a lab
  test, the vitals, the ECGs or the sessions, among the rows the tool returned); earlier rows may come with it. "Last"
  as a date ("last Thursday", "last night", "at last") is no such question.
- `FormularyVerifier`, hospital ("Check the drugs named"): a drug of the hospital's medication list, read once
  through its data source `ehr`, that the answer's own words name must be on a medication list the exchange returned;
  words quoted from a note and what a figure or a table writes in are not the model's own, a drug the question names is
  expected, and a drug the hospital never prescribes is not on the list the check knows.
- `PatientVerifier`, hospital ("Check the patient asked"): when the question names a patient, every figure the
  answer cites is about a patient it names.
- `PeriodVerifier`, auditor ("Check the period asked"): when the question names a year, every figure and table cell
  is for a year it names, read by the first year of its period ("FY2025" and "2025-10-03" are 2025); the cells a
  computation is made of are not judged, and the years they span are expected too, so a growth shown from 2024 to
  2025 passes. An amount in the question ("2000 EUR", "$2000", "2000%") is no year.
- `CustomerVerifier`, company-sales ("Check the customer asked"): when the question names a customer, every figure
  the answer cites is about a customer it names.

A patient or a customer is named by the first word of three letters or more in the name, as a tool result's subject
column holds it. A question the rule does not apply to (one that names no patient, customer or year, or does not ask
for the latest) has nothing to check and passes. company-hr, the hr-contracts airlock, has none: its answers are quotes,
which grounding checks. The rules are the templates' own words (English question words, ISO-like periods, their
entities): potassium for troponin, profit for revenue or a group's average for one person's get past all of them,
and a project adds a verifier for the question shapes of its own domain.

## In the audit trail

The audit line counts the answer's figures, `"figures": {"sourced": 1, "computed": 0, "unsourced": 0,
"mismatch": 0}` (never a value), lists as `evidence` what its figures, tables and quotes cite, and records per tool
call its `args`, the rule version, the SQL hash and `rows_hash`: the sha256 of the rows it returned, as compact
JSON with each row's keys sorted and every number as plain decimal text without trailing zeros. An auditor who
re-runs the governed query at the same revision gets the same hash, or sees that the data changed since ([Security,
Audit trail](security.md#audit-trail)). The arguments are the model's, as it wrote them: it saw a masked column as
`****`, so no masked value is among them, and a call the tool server refused records none. The counts stay in the audit trail (the **Audit** page, the API); the XBRL
audit report carries each exchange's verifier verdicts instead ([Audit](audit.md#the-taxonomy)). The exchange's
span carries the same four counts ([Observability](observability.md#traces)).

## Re-running a tool call

A tool call of an audit line can be run again, as it ran, to see whether the rows behind an answer are still
the rows it was given. What it takes:

- **The same revision.** The MCP server answers only for the revision it loaded. Re-run the call while the airlock
  still serves the line's `revision`, or deploy it again as it was: the revision is the hash of the global, the
  space's and the airlock's documents, so it comes back when all three are as they were.
- **The same user.** The rules are the user's: the call carries a token of the line's `user`, from a client the
  airlock admits. Anyone else's token yields that person's rows, and another hash.
- **The MCP server.** Its NetworkPolicy admits only its own airlock pod, so an operator of the namespace reaches it
  with a port-forward: `kubectl port-forward -n <namespace> svc/<space>-<airlock>-mcp-server 8081`.

Then call the line's tool with its arguments and the line's airlock and revision in `_meta`, as the airlock pod did, and hash the
rows the way the airlock does (`LINE` holds the audit line, `TOKEN` the user's token; the first tool call here):

```bash
jq -c '{jsonrpc: "2.0", id: 1, method: "tools/call",
        params: {name: .tools[0].name, arguments: .tools[0].args,
                 _meta: {airlock, revision}}}' <<<"$LINE" \
  | curl -s -X POST http://localhost:8081/mcp -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d @- \
  | grep -o '{.*' | jq -r '.result.content[0].text' > evidence.json
jq '{rule_version, sql_hash}' evidence.json
python3 rows_hash.py < evidence.json
```

`rows_hash.py`, the airlock's canonical form (`ChatExchange.rowsHash`, which a test pins to this script's output):

```python
import decimal, hashlib, json, sys

def plain(n):
    d = decimal.Decimal(n).normalize()
    return format(d, "f") if d else "0"

rows = json.load(sys.stdin, parse_float=decimal.Decimal)["rows"]
canonical = [
    {k: plain(v) if isinstance(v, (int, decimal.Decimal)) and not isinstance(v, bool) else v
     for k, v in row.items()}
    for row in rows
]
text = json.dumps(canonical, separators=(",", ":"), sort_keys=True, ensure_ascii=False)
print(hashlib.sha256(text.encode()).hexdigest())
```

Compare with the line's tool call: the same `rule_version`, `sql_hash` and hash are the same rows under the same
rules; another `rule_version` or `sql_hash` means the rules or the statement differ, so it is not the same query;
the same rules and statement with another hash mean the data changed since. A `tools` entry without `args` is a
call that failed: there is nothing to re-run.

## The golden set

`conformance/cases/grounding/` holds one set per template, read by both runtimes,: the evidence its tools return (the labels
are the template's own entities', which the CLI's template test checks; `company-hr.json` keeps the HR entities the
template had before it became the hr-contracts airlock) and answers in the format, each with one
injected error or none, with the status every figure must get. `GoldenSetTest` runs them, no model involved:

| Error | Injected as | Caught as |
|---|---|---|
| wrong subject | a row of another person, client or patient; another's row as the user's own | mismatch |
| wrong period | last year's row for this year's question, an earlier reading for the last | mismatch |
| wrong concept | another column, a column the result does not have, another concept's row | mismatch |
| wrong unit | another currency or unit beside the figure or claimed, a ranking across currencies | mismatch |
| wrong scope | a group's average as one person's, a row of another tool result | mismatch |
| typed computation | a sum, difference or growth rate typed in the text | unsourced |
| fabricated | a number typed from nowhere, a reference to evidence that does not exist | unsourced, mismatch |
| typed text fact | a drug or a frequency typed from a row instead of cited | unsourced |
| scale, sign | a scale word or sign beside the figure; a scaled or negated number typed | mismatch, unsourced |
| masked | a masked column cited | mismatch |
| tables, rankings | out of order, a row missing, a row of another tool result | mismatch |

Every attribution error in a cited figure is a mismatch and every typed number unsourced, in all four templates;
the correct answers are all sourced or computed. A typed text fact is in the hospital's set alone, the one template
that marks fact columns, and company-sales' correct answer that writes "overdue" in prose stays clean: nothing marks
it.

```
./gradlew :apps:airlock:test --tests '*GoldenSetTest*'
```
