plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.ai.bom))

    implementation(project(":libs:sdk"))
    implementation(project(":libs:config"))
    implementation(project(":libs:identity"))
    implementation(project(":libs:plugins"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    // Traces over OTLP/HTTP; spans leave the pod only when the controller rendered the endpoint.
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // /actuator/prometheus, for the scraper the chart's NetworkPolicy rule admits
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(testFixtures(project(":libs:identity")))
    testImplementation(testFixtures(project(":libs:plugins")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation(libs.archunit.junit5)
}

tasks.withType<Test>().configureEach {
    systemProperty("aisolate.docker.dir", rootProject.file("docker").absolutePath)
}

// AISOLATE_PLUGINS_DIR is optional: the tests carry their own plugins (`support.HrAcmeTools`,
// `support.PayrollTools`); a pod gets the directory and the version's digest from the controller.
// The templates' data sources are the compose stack's Acme database, one role per schema; the
// server itself has no defaults. Your environment wins over these.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    fun dev(name: String, default: String) =
        environment(name, providers.environmentVariable(name).getOrElse(default))
    dev("AISOLATE_DATASOURCE_0_ID", "hr")
    dev("AISOLATE_DATASOURCE_0_URL", "jdbc:postgresql://localhost:5433/acme")
    dev("AISOLATE_DATASOURCE_0_USERNAME", "aisolate_hr")
    dev("AISOLATE_DATASOURCE_0_PASSWORD", "aisolate_hr")
    dev("AISOLATE_DATASOURCE_1_ID", "clients")
    dev("AISOLATE_DATASOURCE_1_URL", "jdbc:postgresql://localhost:5433/acme")
    dev("AISOLATE_DATASOURCE_1_USERNAME", "aisolate_clients")
    dev("AISOLATE_DATASOURCE_1_PASSWORD", "aisolate_clients")
    // Beside the airlock's bootRun, which keeps actuator on 9464.
    dev("MANAGEMENT_SERVER_PORT", "9465")
}

// One stable name for the image build (Dockerfile COPY).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("tool-server.jar")
}
