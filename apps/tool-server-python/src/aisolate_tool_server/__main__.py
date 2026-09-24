"""Start: documents, token verifier, data sources, the artifact fetched and pinned, its rules; then serve 8081
and 9464. Any problem exits 1 with one line, before anything is served (readiness never goes UP)."""

from __future__ import annotations

import logging
import os
import sys
from collections.abc import Mapping
from pathlib import Path

from aisolate_runtime import artifact, config, server, telemetry
from aisolate_runtime.datasources import Pools
from aisolate_runtime.identity import DEFAULT_ROLES_CLAIM, TokenVerifier
from starlette.types import ASGIApp

from aisolate_tool_server.app import create_app, service_hosts
from aisolate_tool_server.governed import GovernedQueries, PolicySource, PoolsDatabase
from aisolate_tool_server.rules import load_rule_sets

PORT = 8081
log = logging.getLogger("aisolate.toolserver")


def start(env: Mapping[str, str], code_dir: Path = artifact.CODE_DIR,
          fetched_dir: Path = artifact.FETCHED_DIR) -> ASGIApp:
    """The MCP app of the rendered airlock; any problem raises, naming it."""
    configuration = config.load(env.get("AISOLATE_CONFIG_DIR"), env.get("AISOLATE_CONFIG_REVISION"))
    verifier = TokenVerifier.from_env(env.get("AISOLATE_AUDIENCE", "aisolate"), env)
    pools = Pools.from_env(env)
    located = artifact.locate(env, fetched_dir)
    if located is None:
        raise RuntimeError("no tool plugin; the airlock has no code (AISOLATE_CODE_DIGEST, or AISOLATE_PLUGINS_DIR"
                           " for a local run)")
    plugins_dir, digest = located
    catalog, _, code_dir = artifact.load(plugins_dir, digest, code_dir)
    if not catalog.plugins:
        raise RuntimeError(f"no tool plugin in {plugins_dir}")
    for plugin in catalog.plugins:
        if plugin.entities and plugin.data_source not in pools.ids:
            raise RuntimeError(
                f"plugin {plugin.name} reads data source {plugin.data_source}, which this airlock does not define"
            )
        log.info("loaded tool plugin %s reading %s declaring %s and publishing %s", plugin.name,
                 plugin.data_source, [e.name for e in plugin.entities], [t.name for t in plugin.tools()])
    rule_sets = load_rule_sets(code_dir / "rules", catalog.plugins)
    log.info("loaded %d rule set(s): %s", len(rule_sets), list(rule_sets))
    queries = GovernedQueries(
        PolicySource.of(configuration), catalog, rule_sets, PoolsDatabase(pools),
        env.get("AISOLATE_ROLES_CLAIM") or DEFAULT_ROLES_CLAIM,
    )
    return create_app(queries, verifier, service_hosts(configuration))


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    telemetry.setup("tool-server")
    try:
        app = start(os.environ)
    except Exception as problem:
        log.error("the tool server cannot start: %s", str(problem).replace("\n", "; ") or type(problem).__name__)
        sys.exit(1)
    server.run(app, PORT, telemetry.management_app(lambda: True))


if __name__ == "__main__":
    main()
