import decimal
import json
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


def cases_dir() -> Path:
    for parent in Path(__file__).resolve().parents:
        if (parent / "conformance" / "cases").is_dir():
            return parent / "conformance" / "cases"
    raise FileNotFoundError("conformance/cases above " + __file__)


def case_file(relative: str) -> dict[str, Any]:
    return json.loads((cases_dir() / relative).read_text(), parse_float=decimal.Decimal)


@contextmanager
def serving(answers: list[tuple[int, bytes]], token: str) -> Iterator[str]:
    """A backend answering each GET with the next of ``answers`` (the last one repeats), 401 to any other bearer."""

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - http.server's name
            status, body = answers.pop(0) if len(answers) > 1 else answers[0]
            if self.headers.get("Authorization") != f"Bearer {token}":
                status, body = 401, b""
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/v1/code/s/a/x"
    finally:
        server.shutdown()
        server.server_close()
