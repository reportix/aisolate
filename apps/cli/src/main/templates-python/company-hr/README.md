# Acme Contracts

An AIsolate project: the code and the settings of the `${AIRLOCK}` airlock, the one the company's
people ask about their employment contract: position, pay, working time, notice and confidentiality.
The CEO reads every contract, anyone else only their own. `aisolate deploy` builds the project and
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
tests/                            the tests, with tests/support's FakeAIsolateManager standing in for AIsolate
pyproject.toml                    the project's name, Python 3.13 and its one dependency, the SDK
libs/aisolate-sdk/                the AIsolate SDK, the one thing the project depends on
```

## The code

The template's classes: `ContractsContextPreparator` searches the employment contracts in the data
source `hr` for the sections the asking user may read and adds them to the exchange as evidence.
`SessionMemory` keeps each user's last three questions (200 characters each) while the airlock's
process runs. Grounding is AIsolate's: it checks that each quote is the contract's own words and
flags every number typed outside a quote (`docs/verification.md`).

## Data sources

The template's one data source, `hr`, which the admin defines, is the `hr` schema of the company's
database, opened as a role that reads `hr.documents` and writes nothing but its vectors.

## The contracts

`ContractsContextPreparator` reads the `hr.documents` table of the data source `hr`, PostgreSQL
with pgvector, through `aisolate.data_source("hr")`: one row per section of an employment contract
(position and duties, compensation, working time and vacation, notice period, confidentiality),
the employee's e-mail as its owner. It embeds, with the LLM `nomic` (`aisolate.llm("nomic")`), the
sections that have no embedding, then the question, and adds up to five of the closest sections
the asking user may read: the CEO, the role `ceo`, reads every contract, anyone else only the
contract that names their e-mail as owner. The asking user's own sections are marked as theirs, so
"my notice period" is answered from their own contract. When the airlock sees no data source `hr`
or no model `nomic` that embeds, or the database does not answer within a few seconds, the handler
logs one line, adds no passages and the model says it does not know.

## Ask it

In the demo, Jane Doe (`jane`) is the CEO and John Doe (`john`) an employee. Each answer quotes
the contract; a number appears only inside a quote.

John sees only his own contract:

1. What is my notice period? (three months to the end of a calendar month)
2. What is Jane Doe's salary? (I don't know: her contract never reaches his exchange)

Jane, the CEO, reads every contract:

1. What is John Doe's notice period? (three months to the end of a calendar month)

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
