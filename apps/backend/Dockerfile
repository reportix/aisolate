# The backend, installed with the AIsolate UI: its API, deploy, the apply.
# Built from the boot jar (./gradlew :apps:backend:bootJar); non-root; only /tmp is ever written.
# Distroless: a JRE and its libraries, no shell, no package manager.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:8ac5525549d14b8ddd34562f9fc8e6ba4840c995363a1507b17cadf8bb7ab57e
COPY build/libs/backend.jar /app.jar
# uid 10001, numeric: runAsNonRoot checks it and no /etc/passwd entry is needed.
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp"
EXPOSE 8080
ENTRYPOINT ["/usr/bin/java", "-jar", "/app.jar"]
