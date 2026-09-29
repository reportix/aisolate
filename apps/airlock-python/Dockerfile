# The Python airlock: one airlock per pod, as the Java airlock (aisolate/airlock); its project's
# artifact mounted on /plugins. Build context: the repository root, since the SDK and the runtime are path
# dependencies: docker build -f apps/airlock-python/Dockerfile -t aisolate/airlock-python .
# Non-root, numeric uid; only /tmp (and the audit buffer emptyDir) is ever written.
FROM python:3.13-slim@sha256:70729b46c69b4f1e97c4822c1af3df53a1476cf5ddc6c087c0c10bc3a5678c2f AS build
COPY --from=ghcr.io/astral-sh/uv:0.13.5@sha256:32a7bb39b496babbd997b0a39a4d66c126bdaf5661b302bf6f06abda8deca6ff /uv /bin/uv
ENV UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1 UV_PYTHON_DOWNLOADS=never UV_PROJECT_ENVIRONMENT=/app/.venv
COPY libs/sdk-python /src/libs/sdk-python
COPY libs/runtime-python /src/libs/runtime-python
COPY apps/airlock-python /src/apps/airlock-python
WORKDIR /src/apps/airlock-python
# --no-editable: the SDK, the runtime and the airlock land in the venv, nothing points back to /src.
RUN uv sync --frozen --no-dev --no-editable

# Distroless: the interpreter and its libraries, no shell, no package manager. The venv's own
# interpreter is the build image's; the image's python3 runs the venv's site-packages instead.
FROM gcr.io/distroless/python3-debian13:nonroot@sha256:64e54c4b1200da244d80181d98e86fc01d73c8101f107d5baf472152d8e119a2
COPY --from=build /app/.venv /app/.venv
# uid 10001, numeric: runAsNonRoot checks it and no /etc/passwd entry is needed.
USER 10001:10001
ENV PYTHONPATH=/app/.venv/lib/python3.13/site-packages PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8080 9464
ENTRYPOINT ["/usr/bin/python3", "-m", "aisolate_airlock"]
