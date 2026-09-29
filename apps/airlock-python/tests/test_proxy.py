import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from aisolate_airlock import llm

pytestmark = pytest.mark.anyio


def _egress() -> tuple[ThreadingHTTPServer, list[str]]:
    """Envoy's place: records each CONNECT and refuses it."""
    tunnels: list[str] = []

    class Handler(BaseHTTPRequestHandler):
        def do_CONNECT(self):
            tunnels.append(self.path)
            self.send_response(403)
            self.end_headers()

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, tunnels


@pytest.mark.parametrize(("url", "tunnels"), [("https://models.example.com", ["models.example.com:443"]),
                                              ("https://ollama.aisolate.svc:11434", [])],
                         ids=["external", "no-proxy"])
async def test_given_controllers_proxy_env_when_the_model_is_asked_then_https_goes_through_egress_unless_no_proxy(
        monkeypatch, url, tunnels):
    # Arrange
    server, seen = _egress()
    monkeypatch.setenv("HTTPS_PROXY", f"http://127.0.0.1:{server.server_port}")
    monkeypatch.setenv("NO_PROXY", "localhost,.svc,.cluster.local")
    model = llm.Ollama("qwen", url, "qwen", read_timeout=2.0)

    # Act
    with pytest.raises(llm.ModelUnavailable):
        await model.chat([{"role": "user", "content": "q"}])

    # Assert
    assert seen == tunnels
