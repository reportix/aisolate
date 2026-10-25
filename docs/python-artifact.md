# The Python artifact and its manifest

A Python project deploys as one zip, the **artifact**, with `aisolate-manifest.json` inside. The manifest says
what the code contains; the backend reads it and the rule files, and **never imports or runs the project's code**.
The pods import the code and refuse to start when it does not match the manifest. A Java project still deploys its
jar, read by `JarInspector` (moving Java to a manifest is a later step).

Schema: [`python-artifact.schema.json`](python-artifact.schema.json) (JSON Schema 2020-12). Writer:
`libs/sdk-python/src/aisolate/build.py` (`python -m aisolate build`). The pods that load it: [The pods of an airlock](configuration.md#the-pods-of-an-airlock).

## Project layout

```
pyproject.toml              [project] name, requires-python ">=3.13,<3.14", dependencies = ["aisolate", ...]
                            [tool.uv.sources] aisolate = { path = "libs/aisolate-sdk" }
aisolate.yaml               same schema as a Java project's: the main LLM by id (llm), prompt, tools; no credential
libs/aisolate-sdk/          the SDK (pyproject.toml + src/aisolate), written by `aisolate create` (like libs/aisolate-sdk.jar)
src/<package>/              exactly one top-level package
  __init__.py
  tools/                    ToolPlugin subclasses
  pre_llm/                  @context_preparator, @memory classes
  post_llm/                 @verifier classes
rules/<entity>.json         same JSON as Java (docs/configuration.md)
tests/                      pytest: test_given_<state>_when_<action>_then_<outcome>, # Arrange / # Act / # Assert
.env.example, .gitignore    AISOLATE_ENDPOINT, AISOLATE_SPACE; the LLMs and data sources are the admin's, named by id
```

## The zip

```
aisolate-manifest.json
src/<package>/**            the package source; no __pycache__, no *.pyc
rules/*.json
vendor/**                   the project's own dependencies, installed for Linux (may be empty)
```

- Entries sorted, timestamps fixed to 1980-01-01, deflate: the same source gives the same bytes, so the same digest.
- Digest: `sha256:<hex>` of the zip's bytes, the same form as a jar's. It is the airlock's `spec.jar.digest`.
- `vendor/` holds what `[project] dependencies` in `pyproject.toml` need **besides** the SDK and the runtime's own
  pinned set. The SDK ships that set as `aisolate/runtime-constraints.txt`, exported from
  the two images' `uv.lock` (the SDK's tests fail when they drift apart). The build resolves the dependencies
  against it (`uv pip compile -c runtime-constraints.txt --only-binary :all: --python-version 3.13
  --python-platform <arch>-manylinux_2_28`), then installs what the set does not hold with `uv pip install
  --target vendor --no-deps` and the same flags: a pinned package comes from the pods, never a second copy from the
  zip. Each distribution installed is in `vendored[]`; console scripts and uv's own files are left out.
- The build refuses, one line per package, a dependency (direct or not) that needs a pinned package at another
  version, `anyio 4.14.0 (the project's dependencies) clashes with anyio 4.15.1 (the runtime's pinned set): depend
  on a version the runtime has`, since the pods load the runtime's first; and a package without a Linux wheel,
  `docopt has no Linux wheel for Python 3.13 on aarch64: only binary wheels are vendored`. Pin the project's own
  dependencies (`tabulate==0.10.0`): an unpinned one vendors its newest version, so a later build gives another
  digest.
- One architecture per artifact: `--arch x86_64|aarch64` (`amd64`, `arm64` alike), else `AISOLATE_ARCH`, else this
  machine's CPU, as the local cluster runs on it (`aarch64` on Apple Silicon). For an `x86_64` cluster from a Mac:
  `AISOLATE_ARCH=x86_64 aisolate deploy` (from the shell; `.env` does not reach the build). The manifest records it
  (`arch`), and a pod on another CPU refuses to start before importing anything: `the artifact was built for x86_64,
  this node is aarch64: rebuild with --arch aarch64`. The backend does not check the cluster's nodes at deploy (it
  may not read them).
- uv does the vendoring. The `python3.13` build (no uv on the PATH) refuses a project with a dependency of its own,
  `install uv to vendor dependencies (...)`, and builds one without as before. The templates have no dependency of
  their own: `vendor/` is empty.
- No size limit unless the install sets one (`platform.backend.maxArtifactSize`): the pods fetch the artifact from
  the backend at start (§"Delivery and loading"), so numpy or pandas travel as any package does; the zip and what
  it inflates to share the pod's `/tmp` (2Gi), and a bigger artifact starts more slowly.
- No symlinks, no absolute paths, no `..`: the backend and the runtimes refuse such an entry.

## The manifest

```json
{
  "schema": 1,
  "language": "python",
  "sdk": "0.1.0",
  "python": "3.13",
  "arch": "aarch64",
  "package": "acme_hr",
  "plugins": [
    {"name": "hr-tools", "class": "acme_hr.tools.hr_tools.HrTools", "data_source": "hr",
     "identity_attribute": "employee_id", "entities": ["hr.compensation"], "tools": ["read_my_records"]}
  ],
  "tools": [
    {"name": "read_my_records", "description": "Read up to 20 records ...", "plugin": "hr-tools", "data_source": "hr",
     "input_schema": {"type": "object", "properties": {"entity": {"type": "string", "enum": ["hr.compensation"]}},
                     "required": ["entity"]}}
  ],
  "entities": [
    {"name": "hr.compensation", "plugin": "hr-tools", "data_source": "hr",
     "from": "hr.compensation c JOIN hr.employees e ON e.id = c.employee_id",
     "row_key": "employee_id", "subject": "employee_id", "subject_name": "full_name", "period": null,
     "columns": [
       {"name": "employee_id", "expression": "c.employee_id", "type": "number", "unit": null, "unit_column": null, "decimals": null},
       {"name": "gross_salary", "expression": "c.gross_salary", "type": "number", "unit": null, "unit_column": "currency", "decimals": null}
     ]}
  ],
  "handlers": [
    {"name": "HrDocumentsContextPreparator", "class": "acme_hr.pre_llm.documents.HrDocumentsContextPreparator",
     "stage": "contextPreparator", "order": 0, "description": "Read HR documents", "data_sources": ["hr"]},
    {"name": "SessionMemory", "class": "acme_hr.pre_llm.memory.SessionMemory",
     "stage": "conversationMemory", "order": 1, "description": "Remember the conversation"}
  ],
  "rules": ["rules/hr.compensation.json"],
  "vendored": []
}
```

Field by field (every key is required; `null` where the schema allows it):

| Field | Meaning | Java counterpart |
|---|---|---|
| `schema` | manifest format, `1` | — |
| `language` | `python` | the jar |
| `sdk`, `python` | SDK version and Python minor the build imported; a runtime with another Python minor refuses to start | — |
| `arch` | `x86_64` or `aarch64`, the Linux CPU `vendor/` was built for (always written); a pod on another CPU refuses to start | — |
| `package` | the top-level package under `src/` | the jar's classes |
| `plugins[]` | each concrete `ToolPlugin` subclass: `name`, `class` (`module.QualName`), `data_source`, `identity_attribute`, its entity and tool names | `ToolPlugin` |
| `tools[]` | `name`, `description`, `input_schema`, `plugin`, `data_source` (the plugin's) | `Tool` |
| `entities[]` | `name`, `plugin`, `data_source`, `from`, `row_key`, `subject` (defaulted to `row_key`), `subject_name`, `period`, `columns[]` in declaration order: `name`, `expression`, `type` `text`/`number`, `unit`, `unit_column`, `decimals`, and `fact: true` on a text column marked a fact (absent otherwise) | `Entity`, `Column` |
| `handlers[]` | `name` (the class's `__name__`, what `aisolate.yaml` lists), `class`, `stage` (`contextPreparator`, `conversationMemory`, `answerVerifier`: the `aisolate.yaml` key that lists it), `order`, `description`, and `data_sources` when it names any | `HandlerCandidate` (`JarScan`) |
| `rules[]` | the zip's rule files | `RuleSetFiles` |
| `vendored[]` | `{name, version}` of each distribution under `vendor/` | — |

Arrays are sorted by `name` (`rules` by path), so the manifest is deterministic. Keys are snake_case like the tool
result JSON, and an entity is written exactly as the conformance fixtures carry one (`conformance/cases/rules/`), so
both read entities with one loader.

## What the backend does with it (deploy)

1. Read the zip without extracting it to disk; refuse an entry that is a symlink, absolute, or contains `..`.
2. Parse `aisolate-manifest.json` against the schema; refuse `language` ≠ `python` or an unknown `schema`.
3. Build the same `JarInspector.Contents` a jar gives (tools, handlers with stage/order/description, the plugin
   entities, the rule sets) from the manifest and `rules/*.json`, then validate as for a jar (`CatalogCheck`,
   `DeployService.warnings`): every handler and tool the airlock lists exists, every rule file names an entity of
   the manifest, every column a rule names is one of that entity's columns.
4. Store the zip as it stores a jar, and write `spec.jar: {digest, tools, language: python}` into the airlock.
   (`spec.jar` keeps its name until Java moves to a manifest too; `language` defaults to `java`.)

## Delivery and loading (the pods)

- The zip travels like a jar: the backend serves it by digest on its cluster-internal port 8085,
  `GET /v1/code/<space>/<airlock>/<digest>`, to the bearer of the airlock's code token (the audit
  ingest's HMAC scheme over `code/<space>/<airlock>`, a Secret the controller mounts on
  `/var/run/aisolate/code/token`). The controller renders `AISOLATE_CODE_URL` (that URL),
  `AISOLATE_CODE_DIGEST=sha256:<hex>` and `AISOLATE_CODE_TOKEN_FILE`; nothing is mounted, and the product sets no
  size limit (an operator may, `docs/install.md`). Without `AISOLATE_CODE_DIGEST` a local run or a test names a
  directory holding the zip instead, `AISOLATE_PLUGINS_DIR` pinned by `AISOLATE_PLUGIN_DIGESTS`; the controller
  never renders those.
- At start, before serving (readiness stays DOWN until it succeeds; any failure exits non-zero with one log line):
  1. Fetch it (`aisolate_runtime.artifact.fetch`): streamed to `/tmp/aisolate/fetched/aisolate-code.zip` and hashed on
     the way. A connection failure, a 429 or a 5xx is retried, six tries over about 30 s; any other answer (401,
     403, 404) refuses at once; bytes whose sha256 is not `AISOLATE_CODE_DIGEST` refuse the start and leave no file.
     A digest without a URL (a headless install has no backend) refuses too: code needs `headless=false`.
  2. Extract it, streamed, with the same entry checks as the backend, into `/tmp/aisolate/code` (the pod's only
     writable space, an emptyDir; the rootfs is read-only). An artifact inflating beyond the space free there is
     refused before anything is written.
  3. Read the manifest; refuse another `language`, `schema`, Python minor or `arch`.
  4. Append `/tmp/aisolate/code/vendor` and `/tmp/aisolate/code/src` to the **end** of `sys.path`: the runtime's own
     pinned packages, `aisolate` included, win over anything vendored.
  5. `aisolate.build.discover(manifest["package"])` imports the package and every submodule and constructs each
     plugin and handler once (no arguments); `aisolate.build.check(catalog, manifest)` must return no problem.
  6. The tool server reads the rules from `/tmp/aisolate/code/rules/*.json` with the checks `RuleSetFiles` applies to
     a jar's `aisolate/rules/` (no subfolder, at most 1 MiB each, one rule set per entity).
- The tool server serves the tools the airlock's `tools` lists from the catalog; the airlock pod resolves
  its handlers by name from it. Neither reads an LLM or a credential from the code: the airlock's LLMs come
  from its env (`AISOLATE_LLM*`), the data sources' logins from `AISOLATE_DATASOURCE_<i>_*`.
