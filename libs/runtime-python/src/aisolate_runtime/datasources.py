"""The airlock's databases, from the env the controller renders (Java: ``DataSourceConfig``,
``DataSourceLogin``). Read-only sessions, statement timeout 12 s, connect timeout 5 s, socket 15 s; the
airlock's pools for its handlers as ``ExchangeManagerFactory``'s: writable, socket 10 s."""

from __future__ import annotations

import os
import socket
import ssl
import threading
from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import parse_qsl, unquote, urlsplit

import pg8000.dbapi
import pg8000.native

CONNECT_TIMEOUT = 5.0
SOCKET_TIMEOUT = 15.0  # above the statement timeout, so that one cancels a slow query first
STARTUP = {"default_transaction_read_only": "on", "statement_timeout": "12000"}
# The airlock's handlers get what Java's ExchangeManagerFactory hands them: a plain session (a context
# preparator may write, e.g. its embeddings), socket timeout 10 s, no statement timeout.
HANDLER_SOCKET_TIMEOUT = 10.0
MAX_IDLE = 8


@dataclass(frozen=True, slots=True)
class Login:
    """One ``AISOLATE_DATASOURCE_<i>_*`` entry; ``url`` the JDBC URL as rendered."""

    id: str
    url: str
    username: str | None = None
    password: str | None = field(default=None, repr=False)


@dataclass(frozen=True, slots=True)
class Target:
    """A JDBC URL parsed: ``jdbc:postgresql://host[:port]/db[?params]``."""

    host: str
    port: int
    database: str
    params: Mapping[str, str]


def parse_jdbc_url(url: str) -> Target:
    """ValueError for anything but a ``postgresql`` URL with a host and a database."""
    text = url.strip()
    if text[:5].lower() == "jdbc:":
        text = text[5:]
    parts = urlsplit(text)
    database = unquote(parts.path.lstrip("/"))
    if parts.scheme != "postgresql" or not parts.hostname or not database:
        raise ValueError("a data source URL is jdbc:postgresql://host[:port]/database")
    return Target(parts.hostname, parts.port or 5432, database, dict(parse_qsl(parts.query)))


class Pools:
    """One small pool per data source id."""

    def __init__(self, logins: Mapping[str, Login], handlers: bool = False) -> None:
        """``handlers``: the airlock's pools for project code, writable as the Java airlock's."""
        self._startup = {} if handlers else STARTUP
        self._socket_timeout = HANDLER_SOCKET_TIMEOUT if handlers else SOCKET_TIMEOUT
        self._logins = dict(logins)
        self._targets = {id: parse_jdbc_url(login.url) for id, login in self._logins.items()}
        self._idle: dict[tuple[str, str], list[Any]] = {}
        self._lock = threading.Lock()

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ, handlers: bool = False) -> Pools:
        """``AISOLATE_DATASOURCE_<i>_ID``/``_URL``/``_USERNAME``/``_PASSWORD`` from 0 until the first
        missing ``_ID``; ValueError ``AISOLATE_DATASOURCE_<i>_ID is blank``, ``..._URL is blank``,
        ``data source <id> is defined twice``."""
        logins: dict[str, Login] = {}
        i = 0
        while f"AISOLATE_DATASOURCE_{i}_ID" in env:
            prefix = f"AISOLATE_DATASOURCE_{i}_"
            id, url = env[prefix + "ID"], env.get(prefix + "URL", "")
            if not id.strip():
                raise ValueError(f"{prefix}ID is blank")
            if not url.strip():
                raise ValueError(f"{prefix}URL is blank")
            if id in logins:
                raise ValueError(f"data source {id} is defined twice")
            logins[id] = Login(id, url, env.get(prefix + "USERNAME"), env.get(prefix + "PASSWORD"))
            i += 1
        return cls(logins, handlers)

    @property
    def ids(self) -> frozenset[str]:
        return frozenset(self._logins)

    @contextmanager
    def connect(self, id: str) -> Iterator[pg8000.dbapi.Connection]:
        """A DB-API connection (``%s`` params) from the pool of ``id``, rolled back and returned on exit.
        KeyError for an unknown id (callers word their own message)."""
        with self._lease(id, "dbapi") as connection:
            yield connection

    @contextmanager
    def native(self, id: str) -> Iterator[pg8000.native.Connection]:
        """A pg8000 native connection (``:name`` params) for the tool server's compiled statements."""
        with self._lease(id, "native") as connection:
            yield connection

    def close(self) -> None:
        with self._lock:
            idle, self._idle = self._idle, {}
        for connections in idle.values():
            for connection in connections:
                _close(connection)

    @contextmanager
    def _lease(self, id: str, kind: str) -> Iterator[Any]:
        login = self._logins[id]
        with self._lock:
            idle = self._idle.setdefault((id, kind), [])
            connection = idle.pop() if idle else None
        if connection is None:
            connection = self._open(login, self._targets[id], kind)
        try:
            yield connection
            if kind == "dbapi":
                connection.rollback()
        except BaseException:
            # A failed statement may have left the connection broken: never hand it out again.
            _close(connection)
            raise
        with self._lock:
            idle = self._idle.setdefault((id, kind), [])
            if len(idle) < MAX_IDLE:
                idle.append(connection)
                connection = None
        if connection is not None:
            _close(connection)

    def _open(self, login: Login, target: Target, kind: str) -> Any:
        sock = socket.create_connection((target.host, target.port), timeout=CONNECT_TIMEOUT)
        sock.settimeout(self._socket_timeout)
        options: dict[str, Any] = {
            "user": login.username, "password": login.password, "database": target.database,
            "sock": sock, "startup_params": self._startup, "application_name": "aisolate",
        }
        sslmode = target.params.get("sslmode", "prefer" if target.params.get("ssl") == "true" else "disable")
        if sslmode in ("require", "prefer"):
            context = ssl.create_default_context()
            context.check_hostname, context.verify_mode = False, ssl.CERT_NONE
            options["ssl_context"] = context
        elif sslmode in ("verify-ca", "verify-full"):
            options["ssl_context"] = ssl.create_default_context()
        try:
            return (pg8000.dbapi.Connection if kind == "dbapi" else pg8000.native.Connection)(**options)
        except BaseException:
            sock.close()
            raise


def _close(connection: Any) -> None:
    try:
        connection.close()
    except Exception:
        pass
