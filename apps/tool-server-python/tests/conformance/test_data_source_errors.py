import errno
import socket
from typing import Any

import pytest
from conftest import case_file, ids
from pg8000.exceptions import DatabaseError, InterfaceError

from aisolate_tool_server.app import ERROR_TEXTS, error_of

CASES = case_file("errors/data-source.json")
NETWORK = {
    "refused": lambda: ConnectionRefusedError(errno.ECONNREFUSED, "Connection refused"),
    "unknown_host": lambda: socket.gaierror(socket.EAI_NONAME, "Name or service not known"),
    "no_route": lambda: OSError(errno.EHOSTUNREACH, "No route to host"),
    "timeout": lambda: TimeoutError("timed out"),
}


def _failure(spec: dict[str, Any]) -> BaseException:
    """The failure as pg8000 raises it: the server's fields as a dict, a socket's failure re-raised from."""
    if "sqlstate" in spec:
        failure: BaseException = DatabaseError({"S": "FATAL", "C": spec["sqlstate"], "M": "ERROR at SELECT secret"})
    elif "network" in spec:
        failure = InterfaceError("network error")
        failure.__cause__ = NETWORK[spec["network"]]()
        return failure
    else:
        return RuntimeError("plugin returned no Result")
    if "cause" in spec:
        failure.__cause__ = _failure(spec["cause"])
    return failure


@pytest.mark.parametrize("case", CASES["cases"], ids=ids(CASES))
def test_given_failed_read_when_classified_then_error_and_its_sentence_as_java(case: dict[str, Any]) -> None:
    # Arrange
    failure = _failure(case["failure"])

    # Act
    error = error_of(failure)

    # Assert
    assert error == case["error"]
    assert ERROR_TEXTS[error] == CASES["texts"][error]
