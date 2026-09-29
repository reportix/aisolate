"""The spans of one exchange (``ExchangeTracing``): their names and attributes.

A failed span gets status ERROR and ``error.type``, never an exception's message or event: a message may
quote a row, a prompt or a token.
"""

from contextlib import contextmanager

from opentelemetry import context, trace
from opentelemetry.trace import Status, StatusCode
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

SPACE, AIRLOCK, REVISION = "aisolate.space", "aisolate.airlock", "aisolate.revision"
PRE_LLM, POST_LLM = "pre_llm", "post_llm"

tracer = trace.get_tracer("aisolate.airlock")


def describe(airlock) -> dict:
    return {SPACE: airlock.space, AIRLOCK: airlock.id, REVISION: airlock.revision}


def tag(span, key: str, value) -> None:
    """``ExchangeTracing.tag``: the value as its text, as Micrometer exports every tag; nothing for None."""
    if value is not None:
        span.set_attribute(key, str(value).lower() if isinstance(value, bool) else str(value))


def failed(span, error: BaseException) -> None:
    span.set_status(Status(StatusCode.ERROR))
    span.set_attribute("error.type", type(error).__name__)


@contextmanager
def span(name: str, attributes: dict | None = None, root: bool = False):
    """A span current while the block runs; ``root`` starts a trace of its own whatever is current."""
    parent = context.Context() if root else None
    with tracer.start_as_current_span(name, context=parent, record_exception=False,
                                      set_status_on_exception=False) as current:
        for key, value in (attributes or {}).items():
            tag(current, key, value)
        try:
            yield current
        except BaseException as error:
            failed(current, error)
            raise


def trace_id(span=None) -> str | None:
    """The current trace as 32 lower-case hex; None when nothing traces."""
    ctx = (span or trace.get_current_span()).get_span_context()
    return format(ctx.trace_id, "032x") if ctx.is_valid else None


def trace_headers() -> dict[str, str]:
    """``traceparent`` (and ``tracestate``) of the current span, for the tool server."""
    headers: dict[str, str] = {}
    TraceContextTextMapPropagator().inject(headers)
    return headers
