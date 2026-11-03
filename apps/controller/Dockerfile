# The AIsolate controller image: one per installation, in the data-plane namespace.
# Distroless: a JRE and its libraries, no shell, no package manager.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:a90a5fadd78dc37b643da72dca2f744ef67eba625eca7227a5b375b548e6b1c2
COPY build/libs/controller.jar /app.jar
# uid 10001, numeric: runAsNonRoot checks it and no /etc/passwd entry is needed.
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.io.tmpdir=/tmp"
EXPOSE 8082
ENTRYPOINT ["/usr/bin/java", "-jar", "/app.jar"]
