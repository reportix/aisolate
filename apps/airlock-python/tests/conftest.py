import decimal
import json
from pathlib import Path


def conformance_dir() -> Path:
    for parent in Path(__file__).resolve().parents:
        if (parent / "conformance" / "cases").is_dir():
            return parent / "conformance" / "cases"
    raise RuntimeError("no conformance/cases above this test")


def read_case(*parts: str):
    text = conformance_dir().joinpath(*parts).read_text(encoding="utf-8")
    return json.loads(text, parse_float=decimal.Decimal)


from opentelemetry import trace  # noqa: E402
from opentelemetry.sdk.trace import TracerProvider  # noqa: E402
from opentelemetry.sdk.trace.export import SimpleSpanProcessor  # noqa: E402
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter  # noqa: E402

SPANS = InMemorySpanExporter()
_provider = TracerProvider()
_provider.add_span_processor(SimpleSpanProcessor(SPANS))
trace.set_tracer_provider(_provider)
