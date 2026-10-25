# The Python artifact and its manifest

A Python project deploys as one zip, the **artifact**, with `aisolate-manifest.json` inside. The manifest says
what the code contains; the backend reads it and the rule files, and **never imports or runs the project's code**.
The pods import the code and refuse to start when it does not match the manifest.

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
  pinned set (`aisolate/runtime-constraints.txt`).
  A pinned package comes from the pods, never a second copy from the
  zip. Each distribution installed is in `vendored[]`.
- The build refuses, one line per package, a dependency (direct or not) that needs a pinned package at another
  version,
  and a package without a Linux wheel.
  Pin the project's own
  dependencies (`tabulate==0.10.0`): an unpinned one vendors its newest version, so a later build gives another
  digest.
- One architecture per artifact: `--arch x86_64|aarch64` (`amd64`, `arm64` alike), else `AISOLATE_ARCH`, else this
  machine's CPU, as the local cluster runs on it (`aarch64` on Apple Silicon). For an `x86_64` cluster from a Mac:
  `AISOLATE_ARCH=x86_64 aisolate deploy` (from the shell; `.env` does not reach the build). The manifest records it
  (`arch`), and a pod on another CPU refuses to start.
- No symlinks, no absolute paths, no `..`: the backend and the runtimes refuse such an entry.

## The manifest

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

## Delivery and loading (the pods)

- At start, before serving (readiness stays DOWN until it succeeds; any failure exits non-zero with one log line):
  3. Read the manifest; refuse another `language`, `schema`, Python minor or `arch`.
  4. Append `/tmp/aisolate/code/vendor` and `/tmp/aisolate/code/src` to the **end** of `sys.path`: the runtime's own
     pinned packages, `aisolate` included, win over anything vendored.
  5. `aisolate.build.discover(manifest["package"])` imports the package and every submodule and constructs each
     plugin and handler once (no arguments); `aisolate.build.check(catalog, manifest)` must return no problem.
