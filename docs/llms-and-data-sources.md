# LLMs and data sources

What an LLM and a data source are to AIsolate, at which of the three levels each is defined, and how the
documents, the API, the controller, the runtimes and the AIsolate UI carry them.

## The model

An **LLM** (the UI says **model**) is `{id, provider, url, model}`, and for an OpenAI-compatible one an optional
`secretRef`. It has **no type**: no chat model, no embedding model. The same entry chats or embeds, as far as its
model can. `provider` is `ollama` or `openai` (`AirlockDocument.PROVIDERS`, in that order; UI labels "Ollama"
and "OpenAI-compatible"). `url` is the model server's base URL, under the same rules as a data source's URL (a
host, no credential): Ollama's `http://ollama.aisolate.svc:11434`; an OpenAI-compatible one's with its API version,
`https://api.openai.com/v1`, `https://generativelanguage.googleapis.com/v1beta/openai`, or Ollama's own
`http://host.minikube.internal:11434/v1`. `model` is the name the server knows (`granite4.1:8b`,
`nomic-embed-text`, `gpt-4o-mini`). See [API keys](#api-keys).

A **data source** is `{id, url, secretRef}`, as today.

Three levels define both, and an airlock sees all three:

| Level | Defined by | Where |
|---|---|---|
| **global** (AIsolate) | an admin (realm role `aisolate-admin`) | `/v1/llms`, `/v1/data-sources`; the `Global` document |
| **space** | an admin | the space form; the `Space` document |
| **airlock** | an admin or a developer of the space; the project's `aisolate.yaml` | the airlock form; the `Airlock` document |

**Override rule.** The same id at a nearer level replaces the farther one, whole: airlock > space >
global. Ids are otherwise independent: the catalog an airlock sees is the union. Every space inherits every global
entry; any developer of any space may read the global entries and use them from code (by design: a
global data source is open to every space's code).

**Ids.** LLM ids and data source ids both match `^[a-z0-9][a-z0-9_-]{0,40}$` (`Names.DATA_SOURCE_ID`, and
`Names.LLM_ID` with the same pattern); each list holds an id once. LLMs and data sources are separate
namespaces: `hr` may be both.

**The main LLM.** An airlock names the LLM that answers, its agent loop's, by id: `spec.llm: granite`. It
must resolve at deploy; an airlock without one runs nothing. Handler code names any LLM of the catalog by id at run time
(`aisolate.llm("nomic")`); those ids are known only at run time and are never checked at deploy.

**Resolution** (`libs/config` `Configuration`):
- `llmsOf(airlock)`: the Global's `llms`, the space's, the airlock's, put into one map by id in
  that order, a nearer one replacing; returned sorted by id.
- `mainLlmOf(airlock)`: the entry of `llmsOf(airlock)` whose id is the airlock's `spec.llm`.
- `dataSourcesOf(airlock)`: as today with the Global's `dataSources` first; returned sorted by id.

## Documents

A fourth kind, `Global`: at most one per install, named `aisolate`. The backend always writes it (empty lists
when the admin defined nothing); the loader treats a missing one as empty, so a space's documents still load alone.

```yaml
apiVersion: aisolate.dev/v1
kind: Global
metadata: {name: aisolate}
spec:
  llms:
    - {id: granite, provider: ollama, url: http://ollama.aisolate.svc:11434, model: "granite4.1:8b"}
    - {id: nomic, provider: ollama, url: http://ollama.aisolate.svc:11434, model: nomic-embed-text}
    - {id: gpt, provider: openai, url: https://api.openai.com/v1, model: gpt-4o-mini, secretRef: aisolate-llm-gpt-a1b2c3}
  dataSources: []
---
kind: Space
spec:
  llms: [{id: granite, provider: ollama, url: http://…, model: "granite4.1:8b"}]   # replaces spec.llm
  dataSources: [...]
---
kind: Airlock
spec:
  space: acme
  description: …
  preLLM: {...}
  llm: granite                      # the main LLM's id; absent until the deploy brings it, and then the airlock runs nothing
  prompt: "…"                       # was spec.llm.prompt
  tools: [read_records]             # was spec.llm.tools
  postLLM: {...}
  llms: []                          # its own LLMs; optional
  dataSources: [...]
  jar: {...}
```

Records: `Llm(String id, String provider, String url, String model, String secretRef)` replaces today's
four-field `Llm` (a four-argument constructor leaves `secretRef` null);
`GlobalDocument(Metadata metadata, Spec spec)` with `Spec(List<Llm> llms, List<DataSource> dataSources)`;
`SpaceDocument.Spec.llms`, `AirlockDocument.Spec.{llm (String), prompt,
tools, llms}`. `AirlockDocument.tools()` reads `spec.tools`.

The loader refuses (each with file, document and field, as today):
- a second `Global`, or one not named `aisolate`;
- an LLM entry without an id or with a bad one, an id its list holds twice, a provider other than `ollama` or
  `openai`, a blank `url` or one that breaks the URL rules, a blank `model`, an unknown field (an `apiKey`:
  a document names the Secret, never the key);
- an airlock whose `spec.llm` resolves to nothing:
  `hr: llm granite is no model of it, of Space acme or of AIsolate`; one without `spec.llm` is valid and runs nothing.

**Revision.** An airlock serves three documents: its `Space`, itself and the `Global`. A change to
a global entry therefore rolls every airlock's pods, as a space change rolls its airlocks'.

**CRD.** `globals.aisolate.dev`, kind `Global`, namespaced in the release namespace like the other two,
generated by `./gradlew :apps:controller:syncCrds`. The backend's and the controller's RBAC gain `globals` (and
`globals/status` for the controller).

## API

Every path is under `/v1`, as the rest of [api.md](api.md).

| Method and path | Caller | Purpose |
|---|---|---|
| `GET /llms` | admin, developer of any space (`Access.DEVELOPER_ANYWHERE`) | `[{id, provider, url, model, secret_ref, updated_at}]`, sorted by id; `secret_ref` null without a key |
| `GET /llms/{id}` | same | One; 404 `no model named granite` |
| `POST /llms` | admin | Create `{id, provider, url, model, api_key}`; `api_key` is optional and write-only ([API keys](#api-keys)); 409 `id: a model granite already exists` |
| `PUT /llms/{id}` | admin | Edit `provider`, `url`, `model`, `api_key`; the id is fixed; the stored key stays while the `url` does and no new one is typed. `If-Match: <updated_at>` as for a space |
| `DELETE /llms/{id}` | admin | 204, its key's Secret deleted. 400 when an airlock would lose its main LLM (below) |
| `GET /data-sources`, `GET /data-sources/{id}` | admin, developer of any space | Rows `{id, url, secret_ref, updated_at}` |
| `POST /data-sources`, `PUT /data-sources/{id}`, `DELETE /data-sources/{id}` | admin | Rows `{id, url, username, password}` as a space's data source rows: `username` and `password` are write-only and become the Secret `aisolate-ds-<id>-<6 chars>`; a row keeps its Secret only while its `url` stays. 409 `id: a data source hr already exists`; a delete is 400 when an airlock's code reads it and nothing nearer defines it |

Every create, edit and delete applies the `Global` with the same `AIsolate-Cluster` header as a space save.

**Space and airlock forms.** `llms: [{id, provider, url, model, secret_ref, api_key}]` replaces `provider`,
`llm_url`, `chat_model` and `embedding_model`, `secret_ref` and `api_key` as a data source row's `secret_ref` and
`password`; `data_sources` is unchanged. A GET returns the space's or the airlock's own rows only: the
UI shows what is inherited from `GET /llms`, `GET /data-sources` and the space.

**The airlock's run.** `llm` (the main LLM's id), `prompt` and `tools` come from `aisolate.yaml`, which holds no
credentials: an `llms` entry it brings takes no key and names no Secret. An airlock's GET adds
`main_llm: {id, provider, url, model, from}`, `from` one of `airlock`, `space`, `global`, or `null` when
`llm` resolves to nothing: the UI shows that and no other LLM.

**Storage.** Flyway `V1__baseline.sql` (no V2: one baseline, pre-release) gains two tables:

```sql
CREATE TABLE llms (
    id          text        PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9_-]{0,40}$'),
    provider    text        NOT NULL,
    url         text        NOT NULL,
    model       text        NOT NULL,
    secret_ref  text,
    created_by  text,
    created_by_name text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE data_sources (
    id          text        PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9_-]{0,40}$'),
    url         text        NOT NULL,
    secret_ref  text,
    created_by  text,
    created_by_name text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
```

A space's and an airlock's `definition` JSON holds `llms` in place of the four fields, each row with its
`secret_ref`; an airlock's holds `llm`, `prompt` and `tools` too. `V1.1__test_seed.sql` follows.

### Validation and messages

| When | Field | Message |
|---|---|---|
| A deploy whose `llm` resolves to nothing | `llm` | `llm: no model named granite in this airlock's space or globally` |
| A deploy that leaves the airlock without `llm` | `llm` | `llm: name the model that answers, for example granite` |
| A space save that would leave airlocks without their main LLM | `llms` | `llms: acme/hr and acme/sales would have no model granite; define it on their airlock first` |
| A global delete that would | `id` | `id: acme/hr would have no model granite; define it on its space first` |
| An entry | `llms[i].…` | `llms[1].id: granite is listed twice`, `llms[0].provider: bedrock is not one of [ollama, openai]`, `llms[0].model: required`, the URL rules' messages |
| An Ollama entry with a key | `llms[i].api_key` | `llms[0].api_key: an Ollama model takes no API key` |
| A stored key and a new `url`, no new key | `llms[i].api_key` | `llms[0].api_key: required again with a new url` |
| A key without a cluster, or one the cluster refused | `llms[i].api_key` | `…: no cluster is configured to keep it in a Secret`, `…: the cluster did not take the credentials; nothing was saved` |
| A project's entry naming a Secret | `spec.llms[i].secretRef` | `… spec.llms[0].secretRef is AIsolate's to set; give the model's API key in the AIsolate UI` |

The provider/URL checks of today's `AirlockService.unresolved` ("set it here, on the airlock or on the space")
become the one main-LLM check; the data source check there gains the global level.

## What the controller renders

`Renderer.render` resolves once with `llmsOf`, `mainLlmOf` and `dataSourcesOf`; the pods never resolve levels.

**ConfigMap** `<space>-<airlock>-config` gains `Global-aisolate.yaml` (`selectServedDocuments` adds the `Global`).

**Airlock pod env** (`Renderer.buildAirlockDeployment`), replacing `AISOLATE_OLLAMA_URL`, `AISOLATE_OLLAMA_MODEL`
and `AISOLATE_OLLAMA_EMBEDDING_MODEL`:

| Env | Example | Meaning |
|---|---|---|
| `AISOLATE_LLM` | `granite` | the main LLM's id; always one of the `_ID`s below |
| `AISOLATE_LLM_<i>_ID` | `nomic` | catalog entry i: every LLM the airlock sees, ids sorted, indexes contiguous from 0 |
| `AISOLATE_LLM_<i>_PROVIDER` / `_URL` / `_MODEL` | `ollama` / `http://ollama.aisolate.svc:11434` / `nomic-embed-text` | its fields |
| `AISOLATE_LLM_<i>_API_KEY` | from Secret `secretRef`, key `apiKey` | an `openai` entry's key; absent without one |
| `AISOLATE_DATASOURCE_<i>_*` | | as today, global ones included |

The tool server pod gets no `AISOLATE_LLM*` env, as today; its `AISOLATE_DATASOURCE_<i>_*` include the global ones.

**Egress.** The airlock pod's NetworkPolicy opens every catalog LLM's host, not one. `Release` checks every host:
an unmapped host of an airlock's or space's entry makes the airlock Invalid, as today. An unmapped host
of a global entry is a problem on the `Global`'s status, and that entry is left out of every catalog (env and
egress), so one global typo never stops every space; an airlock whose main LLM it is is Invalid
(`llm granite: host … is not in platform.egress.hosts`).

## The runtimes

Both airlock runtimes read the catalog from env (Java `apps/airlock`, a reader beside `ChatConfig`; Python
`apps/airlock-python` `__main__`), never from the documents. Start check, like `DataSourceLogin.check`; each
refuses the start:
`AISOLATE_LLM is blank`, `AISOLATE_LLM_<i>_ID is blank`, `AISOLATE_LLM_<i>_URL is blank`,
`AISOLATE_LLM_<i>_MODEL is blank`, `model <id> is defined twice`, `model <id>: provider <p> is not one of [ollama,
openai]`, `AISOLATE_LLM granite is none of the AISOLATE_LLM_<i>_ID`. Read until the first missing `_ID`; a blank
`_API_KEY` is none. No defaults: the
`qwen3.6:27b-q4_K_M` and `nomic-embed-text` fallbacks go; tests set the env.

The documents loaders (Java `libs/config`, Python `libs/runtime-python` `config.py`) accept the new shape and the
`Global` kind and refuse an airlock whose `spec.llm` resolves to nothing; Python drops its field-by-field `Llm`
resolution.

**The agent loop** asks the main LLM with today's options (temperature `0.1`, `think=false`, `keep_alive=-1m`,
`AISOLATE_MODEL_READ_TIMEOUT`, no retries). The `NO_MODEL_ENDPOINT` check compares the main LLM's provider. An
`openai` main LLM is asked at `POST <url>/chat/completions` (Java: Spring AI's OpenAI model, 5 s connect, the same
read timeout, no retries) with `temperature` `0.1`, the tools as OpenAI functions (`{"type": "function", "function":
{name, description, parameters}}`), the assistant's `tool_calls` with their ids and each result as `{"role": "tool",
"tool_call_id"}`; the final turn's answer format as `response_format` `{"type": "json_schema", "json_schema":
{"name": "json_schema", "strict": false, "schema": …}}` (OpenAI's strict mode refuses the format's optional fields).
Tokens come from `usage.prompt_tokens` / `completion_tokens`.

**From code.** `llm(id)` returns a handle on the catalog entry, or raises at once for an unknown id.
- `embed`: `POST <url>/api/embed` `{"model": <model>, "input": <text or texts>}` → `embeddings`. Ollama answers
  **501** for a model that cannot embed.
- `chat`: `POST <url>/api/chat` `{"model", "messages": [{"role": "user", "content": <prompt>}], "stream": false,
  "think": false, "keep_alive": "-1m", "options": {"temperature": 0.1}}` → `message.content`. Ollama answers
  **400** `… does not support chat` for a model that cannot chat (pinned in the conformance case).
- An `openai` entry instead: `embed` at `POST <url>/embeddings` `{"model", "input"}` → `data[].embedding` in the
  order of `index`; `chat` at `POST <url>/chat/completions` `{"model", "messages": [{"role": "user", "content":
  <prompt>}], "temperature": 0.1}` → `choices[0].message.content`; both with `Authorization: Bearer <key>` when
  the entry has a key, no header otherwise. The same rules read its answers: 501 on embed, 400 `does not support
  chat` on chat (what Ollama's `/v1` answers); a **401** is the refused key.
- Any other failure (unreachable, timeout, 5xx) raises as today's `embed` does.

### SDK

Java (`libs/sdk`, package `com.reportix.aisolate.sdk`):

```java
// AIsolateManager: embed(String) goes
/** The LLM {@code id} of this airlock's catalog: its own, its space's or a global one. */
Llm llm(String id);                       // throws LlmException for an unknown id

public interface Llm {
  float[] embed(String text);
  List<float[]> embed(List<String> texts); // one vector per text, in order
  String chat(String prompt);
}

/** An LLM named wrongly in code: its message is plain and holds no data, so it is logged whole. */
public class LlmException extends IllegalArgumentException { ... }
```

Python (`libs/sdk-python`, exported from `aisolate`):

```python
class AIsolateManager(Protocol):        # embed(text) goes
    def llm(self, id: str) -> Llm: ...  # raises LlmError for an unknown id

class Llm(Protocol):
    @overload
    def embed(self, text: str) -> list[float]: ...
    @overload
    def embed(self, texts: list[str]) -> list[list[float]]: ...
    def chat(self, prompt: str) -> str: ...

class LlmError(ValueError): ...
```

In a handler: `aisolate.llm("nomic").embed(exchange.question)`, Java `aisolate.llm("nomic").embed(exchange.question())`.

### Error messages

Plain, no capability shown, the same in both runtimes (`conformance/cases/llm/errors.json`):

| When | Raised | Message |
|---|---|---|
| `llm(id)` with an id not in the catalog | `LlmException` / `LlmError` | `no model named nomic in this airlock's space or globally` |
| `embed` answered 501 | same | `granite4.1:8b (model granite) cannot embed: name an embedding model such as nomic in aisolate.llm(...)` |
| `chat` answered 400 `does not support chat` | same | `nomic-embed-text (model nomic) cannot chat: name a chat model such as granite in aisolate.llm(...)` |
| `openai`: `embed` or `chat` answered 401 | same | `gpt-4o-mini (model gpt): the API key was refused; set it again on the model in AIsolate`; neither the key nor the server's answer (which may quote it masked) is in it |

`such as nomic` and `such as granite` are fixed text, not looked up. A handler that fails with `LlmException` /
`LlmError`, or on a data source id the airlock does not have (`<space>/<airlock> has no data source <id>`), is
logged with its message, not only its type: `handler <n> failed at <stage>: <message>` (a verifier's `WARN` note
likewise `verifier failed: <message>`); every other exception keeps today's type-only line.

## Audit and telemetry

- The audit event's `model` stays `{provider, name, calls, input_tokens, output_tokens}` of the **main** LLM and
  counts its calls only; a handler's `embed` or `chat` is not in it (today's `embed` is not either).
- Every model span carries `aisolate.llm=<id>` beside `gen_ai.request.model=<model>`: the loop's `chat <model>`
  spans, a handler's `embedding` span and its `chat <model>` span.
- `gen_ai_client_token_usage_total` is unchanged: its `gen_ai_request_model` label is the model name, its
  `gen_ai_system` the provider (`ollama`, `openai`).

## The AIsolate UI

The rail's top level is Dashboard, Spaces, **LLMs**, **Data sources**, Audit; LLMs and Data sources show to an
admin and to a developer of any space, editable by the admin alone (`/llms`, `/llms/new`, `/llms/[id]`,
`/llms/[id]/edit`, and the same under `/data-sources`). A space's and an airlock's **LLMs** page (path `llms`,
replacing `llm`) and **Data sources** page list, GitLab Source style as today, "Inherited from AIsolate" (global),
"Inherited from <space>" (on an airlock) and its own, an own entry with an inherited id marked as replacing it.
The model form is id, provider (Ollama, OpenAI-compatible), URL, model, and for an OpenAI-compatible one an
optional API key typed once; no chat or embedding model anywhere. The models table shows the provider and the key as
"Kept as a Secret" or none, and ends in an Actions menu (⋮): View on every row, Edit and Delete on the rows of the
level's own. View opens above the list: the row's fields read-only, then its Secret's keys
(the API key; a data source's username and password) masked as "••••••••", each with an eye and a copy. For an
admin the first eye or copy fetches the values once (one recorded reveal, after a sign-in fresher than five
minutes); they stay for 30 seconds or until Close. Everyone else sees a lock: "Only an admin can reveal it". An
airlock's graph and details show its main LLM alone (`main_llm`).

## API keys

As a data source's password is: an `openai` entry's key is typed once in the model form (the
write-only `api_key`), becomes the Secret `<owner>-llm-<id>-<6 chars>` (`aisolate-llm-gpt-…` for a global one,
`<space>-llm-…`, `<space>-<airlock>-llm-…`) with key `apiKey`, owned by the Global, Space or Airlock resource, and
the row stores only its name (`secret_ref`, the documents' `secretRef`); the API never returns the key in a
list or a form. The backend also seals it, AES-256-GCM, into its table `credentials` under that name; an admin
reads it back with `POST /v1/credentials/{secretRef}/reveal` ([API](api.md#post-v1credentialssecretrefreveal)),
the same call for a data source's login. A row keeps
its Secret while its `url` stays and no new key is typed; a new `url` needs the key again; an `ollama` row names
none, so switching a model to Ollama, or deleting it, deletes its Secret once the save commits. The key is
optional: a server such as Ollama's `/v1` needs none. A project's models take none: `aisolate.yaml` holds no
credentials, and a deploy refuses a `secretRef` there; a project's `Airlock` that lists a model the AIsolate UI
gave a key keeps it while the `url` stays. The controller renders `AISOLATE_LLM_<i>_API_KEY` from the Secret into
the airlock pod alone; a reset deletes the Secrets the resources name, as data sources'.
