# The Python tool server image: one pod per airlock, its zip mounted from a ConfigMap
# (AISOLATE_PLUGINS_DIR). Build from the repository root: docker build -f apps/tool-server-python/Dockerfile .
FROM python:3.13-slim@sha256:e48f59cdba69a3cbd9ed2cf0f348d90d45a8d350d98134cf1070bc106ee9ed90 AS build
COPY --from=ghcr.io/astral-sh/uv:0.13.7@sha256:96f40d3904aad8d0bd010539001d36c8e2188b981e1334471a90c4a1d93bb490 /uv /bin/uv
ENV UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1 UV_PYTHON_DOWNLOADS=never UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /src
COPY libs/sdk-python libs/sdk-python
COPY libs/runtime-python libs/runtime-python
COPY apps/tool-server-python apps/tool-server-python
# The runtime's pinned set exactly as locked; the image refuses to build with a non-permissive license.
RUN cd apps/tool-server-python && uv sync --frozen --no-dev --no-editable \
    && /app/.venv/bin/python /src/libs/runtime-python/check_licenses.py

# Distroless: the interpreter and its libraries, no shell, no package manager. The venv's own
# interpreter is the build image's; the image's python3 runs the venv's site-packages instead.
FROM gcr.io/distroless/python3-debian13:nonroot@sha256:c3ed2b61f3c4e410685565ceb3c818197b552832521b39d53eca5d2d9d0dc1e1
COPY --from=build /app/.venv /app/.venv
# Read-only root filesystem: no bytecode written at run time; /tmp is the pod's emptyDir.
ENV PYTHONPATH=/app/.venv/lib/python3.13/site-packages PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 TMPDIR=/tmp
# uid 10001 as the Java images: numeric, so runAsNonRoot can check it; no /etc/passwd entry is needed.
USER 10001:10001
EXPOSE 8081 9464
ENTRYPOINT ["/usr/bin/python3", "-m", "aisolate_tool_server"]
