import io
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from aisolate_airlock import audit


class Ingest:
    """The backend's ingest: answers each POST with the next scripted status, records what it took."""

    def __init__(self, *statuses):
        self.statuses = list(statuses)
        self.received: list[tuple[str, bytes]] = []
        ingest = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                status = ingest.statuses.pop(0) if ingest.statuses else 201
                if status in (200, 201):
                    ingest.received.append((self.headers["Authorization"], body))
                self.send_response(status)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/v1/audit/events"


def _until(condition, seconds=5.0):
    deadline = time.monotonic() + seconds
    while not condition() and time.monotonic() < deadline:
        time.sleep(0.01)
    return condition()


@pytest.fixture
def token(tmp_path):
    path = tmp_path / "token"
    path.write_text("code-token\n")
    return path


def test_given_ingest_away_then_back_when_lines_offered_then_delivered_in_order_with_the_token(tmp_path, token):
    # Arrange
    ingest = Ingest(503, 503)
    sink = audit.AuditSink(ingest.url, str(token), str(tmp_path / "buffer"), 1 << 20, True, 0.01, 0.05)
    sink.start()

    # Act
    for n in range(3):
        assert sink.offer(f'{{"n":{n}}}')

    # Assert
    assert _until(lambda: len(ingest.received) == 3)
    assert ingest.received == [("Bearer code-token", f'{{"n":{n}}}'.encode()) for n in range(3)]
    assert _until(lambda: sink.bytes == 0 and not list((tmp_path / "buffer").iterdir()))
    sink.close()


def test_given_line_refused_with_400_when_sent_then_dropped_and_the_next_goes_on(tmp_path, token):
    # Arrange
    ingest = Ingest(400)
    sink = audit.AuditSink(ingest.url, str(token), str(tmp_path), 1 << 20, True, 0.01, 0.05)
    sink.start()

    # Act
    sink.offer('{"n":0}')
    sink.offer('{"n":1}')

    # Assert
    assert _until(lambda: [body for _, body in ingest.received] == [b'{"n":1}'])
    sink.close()


@pytest.mark.parametrize(("fail_closed", "accepting", "kept"), [(True, False, True), (False, True, False)],
                         ids=["fail-closed", "fail-open"])
def test_given_full_buffer_when_offered_then_fail_closed_refuses_questions_else_drops_lines(tmp_path, fail_closed,
                                                                                           accepting, kept):
    # Arrange
    sink = audit.AuditSink("http://127.0.0.1:9/v1/audit/events", None, str(tmp_path), 4, fail_closed)
    sink.dir.mkdir(exist_ok=True)
    sink.offer('{"n":0}')

    # Act
    second = sink.offer('{"n":1}')

    # Assert
    assert sink.accepting() is accepting
    assert second is kept


def test_given_lines_left_by_an_earlier_start_when_started_then_sent_first_and_numbering_goes_on(tmp_path, token):
    # Arrange
    (tmp_path / f"{7:019d}.json").write_text('{"n":"old"}')
    (tmp_path / "8.tmp").write_text("half")
    ingest = Ingest()
    sink = audit.AuditSink(ingest.url, str(token), str(tmp_path), 1 << 20, True, 0.01, 0.05)

    # Act
    sink.start()
    sink.offer('{"n":"new"}')

    # Assert
    assert _until(lambda: len(ingest.received) == 2)
    assert [body for _, body in ingest.received] == [b'{"n":"old"}', b'{"n":"new"}']
    assert not (tmp_path / "8.tmp").exists()
    sink.close()


def test_given_ingest_and_stdout_when_recorded_then_line_alone_on_stdout_and_buffered(tmp_path):
    # Arrange
    out = io.StringIO()
    sink = audit.AuditSink("http://127.0.0.1:9/v1/audit/events", None, str(tmp_path), 1 << 20, True)
    sink.dir.mkdir(exist_ok=True)
    log = audit.AuditLog(sink, stdout=True, out=out)
    line = audit.refusal(datetime(2026, 10, 16, 20, 15, 30, 123000).astimezone(), None,
                         "s", "a", "r", "u@x", "c", "hi", "user", 3)

    # Act
    log.record(line)

    # Assert
    assert out.getvalue().startswith('{"ts":') and out.getvalue().endswith("}\n")
    assert [f.read_text() for f in tmp_path.iterdir()] == [out.getvalue().strip()]
