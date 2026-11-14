# The airlock core: one airlock per pod, the documents mounted, the jars in /plugins. The same image in
# every install. Built from the boot jar (./gradlew :apps:airlock:bootJar);
# non-root; only /tmp is ever written. Distroless: a JRE and its libraries, no shell, no package manager.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:1c4ced761d17961c900cba60c33c8c3c46edbdadaa9d5ef9806eb5a6824fd406
COPY build/libs/airlock.jar /app.jar
# uid 10001, numeric: runAsNonRoot checks it and no /etc/passwd entry is needed.
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp"
EXPOSE 8080
ENTRYPOINT ["/usr/bin/java", "-jar", "/app.jar"]
