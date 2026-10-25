"""Spans, metrics and the management app on 9464 (docs/observability.md)."""

from __future__ import annotations

import os
from collections.abc import Callable, Sequence

import httpx2
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.common.trace_encoder import encode_spans
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import ReadableSpan, TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SpanExporter, SpanExportResult
from opentelemetry.sdk.trace.sampling import ALWAYS_ON
from opentelemetry.trace import Tracer
from prometheus_client import CONTENT_TYPE_LATEST, REGISTRY, disable_created_metrics, generate_latest
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

# Micrometer exports no *_created series; the dashboards read the same names from both runtimes.
disable_created_metrics()


def otlp_traces_url(endpoint: str | None) -> str | None:
    """``MANAGEMENT_OTLP_TRACING_ENDPOINT`` as the base URL: trailing ``/`` stripped, ``/v1/traces``
    appended unless already there; None when blank."""
    if endpoint is None or not endpoint.strip():
        return None
    base = endpoint.strip().rstrip("/")
    return base if base.endswith("/v1/traces") else base + "/v1/traces"


class OtlpHttpExporter(SpanExporter):
    """OTLP/HTTP protobuf over httpx2: the gRPC exporter would need port 4317, the HTTP one requests."""

    def __init__(self, url: str) -> None:
        self.url = url
        self.client = httpx2.Client(timeout=httpx2.Timeout(10.0, connect=5.0))

    def export(self, spans: Sequence[ReadableSpan]) -> SpanExportResult:
        try:
            response = self.client.post(
                self.url,
                content=encode_spans(spans).SerializeToString(),
                headers={"Content-Type": "application/x-protobuf"},
            )
        except Exception:
            return SpanExportResult.FAILURE
        return SpanExportResult.SUCCESS if response.status_code < 300 else SpanExportResult.FAILURE

    def shutdown(self) -> None:
        self.client.close()


def setup(service_name: str) -> Tracer:
    """A tracer provider (sampling 1.0) exporting OTLP/HTTP protobuf in batches to the endpoint above
    with httpx2; without one, spans still get trace ids and go nowhere. Inbound trace headers are the
    caller's business: the apps start a new trace per request."""
    provider = TracerProvider(resource=Resource.create({"service.name": service_name}), sampler=ALWAYS_ON)
    url = otlp_traces_url(os.environ.get("MANAGEMENT_OTLP_TRACING_ENDPOINT"))
    if url is not None:
        provider.add_span_processor(BatchSpanProcessor(OtlpHttpExporter(url)))
    trace.set_tracer_provider(provider)
    return trace.get_tracer(service_name)


def management_app(ready: Callable[[], bool]) -> Starlette:
    """``/actuator/health/liveness`` (always UP while serving), ``/actuator/health/readiness`` and
    ``/actuator/health`` (``ready()``): 200 ``{"status":"UP"}`` / 503 ``{"status":"DOWN"}``;
    ``/actuator/prometheus`` (the default registry, text format). Nothing else; no token."""

    def status(up: bool) -> Response:
        return JSONResponse({"status": "UP" if up else "DOWN"}, status_code=200 if up else 503)

    async def liveness(_: Request) -> Response:
        return status(True)

    async def readiness(_: Request) -> Response:
        return status(ready())

    async def prometheus(_: Request) -> Response:
        return Response(generate_latest(REGISTRY), media_type=CONTENT_TYPE_LATEST)

    return Starlette(routes=[
        Route("/actuator/health/liveness", liveness),
        Route("/actuator/health/readiness", readiness),
        Route("/actuator/health", readiness),
        Route("/actuator/prometheus", prometheus),
    ])
