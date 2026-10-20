"""The airlock pod: read what the controller rendered (runtime-contract §1.2), load the airlock and its code,
serve 8080 and the management port 9464. Any problem at start is one log line and exit 1."""

from __future__ import annotations

import logging
import os
import re
import sys

import prometheus_client

from aisolate import Stage
from aisolate_runtime import artifact, config, datasources, server, telemetry
from aisolate_runtime.identity import TokenVerifier

from aisolate_airlock import audit
from aisolate_airlock.app import Settings, create_app
from aisolate_airlock.exchange import AgentLoop, Airlock, resolve_handlers
from aisolate_airlock import llm
from aisolate_airlock.mcp_client import ToolServer

log = logging.getLogger("aisolate_airlock")

_DURATION = re.compile(r"^(\d+)(ms|s|m|h|d)?$")
_ISO = re.compile(r"^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$", re.IGNORECASE)
_SIZE = re.compile(r"^(\d+)(B|KB|MB|GB|TB)?$", re.IGNORECASE)


def seconds(text: str) -> float:
    """A Spring duration: ``8m``, ``90s``, ``500ms``, ISO ``PT8M``; a bare number is milliseconds."""
    text = text.strip()
    if match := _DURATION.match(text):
        return int(match[1]) * {"ms": 0.001, "s": 1, "m": 60, "h": 3600, "d": 86400, None: 0.001}[match[2]]
    if (match := _ISO.match(text)) and any(match.groups()):
        hours, minutes, secs = match.groups()
        return int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(secs or 0)
    raise ValueError(f"not a duration: {text}")


def size(text: str) -> int:
    """A Spring data size: ``64MB`` is 64 MiB; a bare number is bytes."""
    match = _SIZE.match(text.strip())
    if not match:
        raise ValueError(f"not a data size: {text}")
    return int(match[1]) * 1024 ** ["B", "KB", "MB", "GB", "TB"].index((match[2] or "B").upper())


def airlock_of(configuration: config.Configuration, airlock_id: str) -> Airlock:
    """The one airlock this pod serves (``AirlockDefinition.from``)."""
    v = configuration.airlock(airlock_id)
    if v is None:
        raise ValueError(f"AISOLATE_AIRLOCK {airlock_id} is no served airlock")
    return Airlock(space=v.space, id=v.name, revision=configuration.revision,
                   model=v.main_llm.provider, users=tuple(v.access.users), callers=tuple(v.access.callers),
                   tools=tuple(v.tools), data_sources=tuple(d.id for d in v.data_sources), system_prompt=v.prompt,
                   context_preparators=tuple(v.handlers[Stage.CONTEXT_PREPARATOR]),
                   memories=tuple(v.handlers[Stage.CONVERSATION_MEMORY]),
                   verifiers=tuple(v.handlers[Stage.ANSWER_VERIFIER]), description=v.description or "",
                   clients=tuple(v.access.clients))


def main(env=os.environ) -> None:
    prometheus_client.disable_created_metrics()  # Micrometer's series have no _created twin
    logging.basicConfig(level=logging.INFO, stream=sys.stdout,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        configuration = config.load(env.get("AISOLATE_CONFIG_DIR"), env.get("AISOLATE_CONFIG_REVISION"))
        airlock = airlock_of(configuration, env.get("AISOLATE_AIRLOCK", ""))
        handlers = []
        if located := artifact.locate(env):
            catalog, _, _ = artifact.load(*located)
            handlers = list(catalog.handlers)
        resolve_handlers(handlers, airlock)  # a listed handler the code lacks fails the start
        pools = datasources.Pools.from_env(env, handlers=True)  # writable for project code, as Java's
        verifier = TokenVerifier.from_env()
        telemetry.setup("aisolate-airlock")
        read_timeout = seconds(env.get("AISOLATE_MODEL_READ_TIMEOUT") or "90s")
        main_llm, llms = llm.catalog(env, read_timeout)
        sink = audit.AuditSink(env.get("AISOLATE_AUDIT_URL"), env.get("AISOLATE_AUDIT_TOKEN_FILE"),
                               env.get("AISOLATE_AUDIT_BUFFER_DIR") or "/var/lib/aisolate/audit",
                               size(env.get("AISOLATE_AUDIT_BUFFER_MAX") or "64MB"),
                               (env.get("AISOLATE_AUDIT_FAIL_CLOSED") or "true").lower() == "true")
        sink.start()
    except Exception as failed:  # noqa: BLE001 - no partial configuration is ever served
        log.error("the airlock cannot start: %s", failed)
        sys.exit(1)
    audit_log = audit.AuditLog(sink, (env.get("AISOLATE_AUDIT_STDOUT") or "true").lower() == "true")
    agent = AgentLoop(llms[main_llm], ToolServer(env.get("AISOLATE_TOOL_SERVER_URL") or "http://localhost:8081/mcp"),
                      handlers, audit_log, seconds(env.get("AISOLATE_EXCHANGE_MAX_DURATION") or "8m"))
    app = create_app(airlock, agent, verifier, audit_log,
                     Settings(env.get("AISOLATE_PUBLIC_URL") or "http://localhost:8080",
                              env.get("AISOLATE_ISSUER_URI") or "",
                              env.get("AISOLATE_CORS_ALLOWED_ORIGINS") or "*"),
                     pools=pools, llms=llms)
    try:
        server.run(app, 8080, telemetry.management_app(lambda: True))
    finally:
        sink.close()
        pools.close()


if __name__ == "__main__":
    main()
