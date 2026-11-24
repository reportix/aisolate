# The airlock core: one airlock per pod, the documents mounted, the jars in /plugins. The same image in
# every install. Built from the boot jar (./gradlew :apps:airlock:bootJar);
# non-root; only /tmp is ever written. Distroless: a JRE and its libraries, no shell, no package manager.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:59bbb349c9752fc57b75744b41b1614c9dabe8bb4869523aabd5a4288f6c09a0
COPY build/libs/airlock.jar /app.jar
# uid 10001, numeric: runAsNonRoot checks it and no /etc/passwd entry is needed.
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp"
EXPOSE 8080
ENTRYPOINT ["/usr/bin/java", "-jar", "/app.jar"]
