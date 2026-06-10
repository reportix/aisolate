plugins {
    `java-library`
    `java-test-fixtures`
}

// What every AIsolate process shares about identity: the resource-server security, the roles
// claim, the principal, the RFC 9728 document. No database, no model, no tool.
dependencies {
    api(platform(libs.spring.boot.bom))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // TracingConfig, which the airlock and the tool server import; the backend sends no spans.
    compileOnly("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    // OtelSpan: a failed span records its error type, never the message.
    compileOnly("io.micrometer:micrometer-tracing-bridge-otel")

    // The shared Keycloak, tokens and HTTP of every app's integration tests.
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi("org.testcontainers:testcontainers")
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
