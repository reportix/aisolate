# Acme Contracts

An AIsolate project: the code and the settings of the `${AIRLOCK}` airlock, the one the company's
people ask about their employment contract: position, pay, working time, notice and confidentiality.
The CEO reads every contract and every term, anyone else only their own. `aisolate deploy` builds the project and
deploys it, together with `aisolate.yaml`, into the space `AISOLATE_SPACE` names in `.env`. The
space comes first, from the AIsolate UI, with the roles that may ask; the LLMs and the data sources
are the admin's, and the project names them by id. The full walk-through is `docs/develop.md` in the
AIsolate repository.

## The tree

```
aisolate.yaml                     the airlock: its name, description and settings
.env, .env.example                AISOLATE_ENDPOINT, AISOLATE_SPACE; .env is never committed
src/acme_hr/                    the code, by stage
  pre_llm/contracts.py            ContractsContextPreparator, a @context_preparator
  pre_llm/memory.py               SessionMemory, a @memory
  tools/contract_tools.py         ContractTools, a ToolPlugin: the tools read_contract_terms and fetch_salary
  post_llm/contract_terms.py      ContractTermsVerifier, a @verifier
rules/hr.contract_terms.json      who reads which rows of hr.contract_terms, by role
rules/hr.salary.json              the same for the salary alone
tests/                            the tests, with tests/support's FakeAIsolateManager standing in for AIsolate
pyproject.toml                    the project's name, Python 3.13 and its one dependency, the SDK
libs/aisolate-sdk/                the AIsolate SDK, the one thing the project depends on
```

## The code

The template's classes: `ContractsContextPreparator` searches the employment contracts in the data
source `hr` for the sections the asking user may read and adds them to the exchange as evidence.
`SessionMemory` keeps each user's last three questions (200 characters each) while the airlock's
process runs. `ContractTools` publishes two tools on AIsolate's tool server: `read_contract_terms`,
the terms of the contracts as the HR system keeps them, and `fetch_salary`, the salary alone (The
terms). `ContractTermsVerifier` checks every figure the answer cites from them against the signed
contract (The verifier). Grounding is AIsolate's: it checks that each figure is the cell it cites
and each quote the contract's own words, and flags every number typed outside both
(`docs/verification.md`).

## Data sources

The template's one data source, `hr`, which the admin defines, is the `hr` schema of the company's
database, opened as a role that reads `hr.documents` and `hr.contract_terms` and writes nothing but
the documents' vectors.

## The contracts

`ContractsContextPreparator` reads the `hr.documents` table of the data source `hr`, PostgreSQL
with pgvector, through `aisolate.data_source("hr")`: one row per section of an employment contract
(position and duties, compensation, working time and vacation, notice period, confidentiality),
the employee's e-mail as its owner. It embeds, with the LLM `gpt-embedding` (`aisolate.llm("gpt-embedding")`), the
sections that have no embedding, then the question, and adds up to five of the closest sections
the asking user may read: the CEO, the role `ceo`, reads every contract, anyone else only the
contract that names their e-mail as owner. The asking user's own sections are marked as theirs, so
"my notice period" is answered from their own contract. When the airlock sees no data source `hr`
or no model `gpt-embedding` that embeds, or the database does not answer within a few seconds, the handler
logs one line, adds no passages and the model says it does not know.

## The terms

`ContractTools` is the project's tool plugin. Its entity `hr.contract_terms` is the terms of each
employment contract as the HR system keeps them, one row per employee: position, department, start
date, salary, bonus, weekly hours, vacation days, probation and notice in months, non-compete; what
the signed contract in `hr.documents` states, transcribed at signing and kept current by its
amendments. `hr.salary` is the same table with the salary alone. The tool `read_contract_terms`
reads the rows the asking user may see, with the filters the question calls for; `fetch_salary`
answers a salary question and nothing else. Who sees which rows is `rules/hr.contract_terms.json`
and `rules/hr.salary.json`, by role: `employee` reads the row whose `email` is the token's
(`email == principal.email`), `ceo` every row; both hide the e-mail column. The e-mail is each
entity's subject and the plugin's identity attribute, so AIsolate marks the asking user's own row
`self`: a figure the model says is about `self` must come from that row. The plugin names its data
source, `hr`, and holds no credential and no SQL: every query goes through the governed query
AIsolate hands the tool.

## The verifier

`ContractTermsVerifier` runs after the model answered. Every figure the answer cites from
`hr.contract_terms` or `hr.salary` (salary, bonus, weekly hours, vacation days: the terms a
contract states in digits) must be what that employee's signed contract states: its sections and
its amendments, the last statement of a term being the current one. The contract is read from the
data source `hr` as filed, never from the passages of the exchange: retrieval ranks them by
likeness and may leave an amendment out. The verdict names the section: "82,000 EUR is in John
Doe's contract: Amendment 1 March 2026". A figure no section states, or one an amendment has
superseded, is a WARN, never a block: "32 days is not in John Doe's contract: Working time and
vacation says 30 days" is a number mistyped in the HR system or an amendment not yet filed, which
is what an auditor looks for; a contract that could not be read is a WARN too. A term in words
("three months") is not compared. Two answers FAIL. One names, or answers a question that names,
an employee of whom no row and no passage was read, and is not "I don't know", whatever the
question says about who is asking: "nothing of Jane Doe was read, so the answer had to be I don't
know". The other holds a figure AIsolate marked a mismatch: the answer says it is about someone or
something the row is not. The check is deterministic, regular expressions over the contract's
sentences; no model.

## Ask it

In the demo, Jane Doe (`jane`) is the CEO and John Doe (`john`) an employee. A figure is a cell of
the HR system's row, a wording the contract's own words; the verdict beside the answer says whether
the figure is what the signed contract states.

John sees his own row and his own contract alone:

1. What is my gross salary? (82,000 EUR from his row, marked self; the verifier: in his contract, Amendment 1 March 2026)
2. How many vacation days do I get? (32 days from the HR system; the verifier warns: Working time and vacation says 30 days)
3. What does my contract say about the notice period? (a quote: three months to the end of a calendar month)
4. What is Jane Doe's salary? (I don't know: her row and her contract never reach his exchange)

Jane, the CEO, reads every row and every contract:

1. Who has a non-compete clause? (the rows with non_compete yes: her own)
2. What is John Doe's notice period? (three months to the end of a calendar month)

## Commands

[uv](https://docs.astral.sh/uv/) runs the project; it installs Python 3.13 when the machine has none.

- `uv run pytest` runs the tests.
- `uv run python -m aisolate build` writes `build/aisolate/acme_hr.zip`, the artifact: the
  package and `aisolate-manifest.json`, which lists its handlers.
- `aisolate build` builds the artifact the same way, with uv, or with Python 3.13 when the
  machine has no uv.
- `aisolate login` signs you in through your browser, once; the session lasts while you use it.
- `aisolate deploy` builds and deploys the code and `aisolate.yaml` into the space `AISOLATE_SPACE`
  names, then prints the airlock, `${SPACE}/${AIRLOCK}`, and its endpoint.
