"""One process serving its API port and the management port 9464."""

from __future__ import annotations

import asyncio
import contextlib
import signal

import uvicorn
from starlette.types import ASGIApp

MANAGEMENT_PORT = 9464


class _Quiet(uvicorn.Server):
    """Signals are handled once for both servers, below."""

    @contextlib.contextmanager
    def capture_signals(self):  # type: ignore[override]
        yield


def run(app: ASGIApp, port: int, management: ASGIApp) -> None:
    """Serve ``app`` on ``0.0.0.0:port`` and ``management`` on 9464 in one event loop until SIGTERM."""

    async def serve() -> None:
        servers = [
            _Quiet(uvicorn.Config(app, host="0.0.0.0", port=port, access_log=False, log_config=None)),
            _Quiet(uvicorn.Config(management, host="0.0.0.0", port=MANAGEMENT_PORT, access_log=False,
                                  log_config=None, lifespan="off")),
        ]

        def stop() -> None:
            for server in servers:
                server.should_exit = True

        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, stop)
        tasks = [asyncio.create_task(s.serve()) for s in servers]
        # When either stops (a bind failure included), stop the other: never half a pod.
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        stop()
        await asyncio.gather(*tasks, return_exceptions=True)

    asyncio.run(serve())
