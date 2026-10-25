plugins {
    java
    alias(libs.plugins.spring.boot)
}

// The airlock core: one airlock per pod, the documents mounted, the jars in /plugins. The same image
// in every install; nothing of the AIsolate UI lives here.
dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.ai.bom))
    implementation(project(":libs:config"))
    implementation(project(":libs:identity"))
    implementation(project(":libs:plugins"))
    implementation(project(":libs:sdk"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    // OpenAI-compatible models: the model alone, no autoconfiguration (one client per catalog entry)
    implementation("org.springframework.ai:spring-ai-openai") {
        // The OpenAI SDK's swagger annotations: nothing here reads them
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }
    implementation(libs.mcp.sdk)
    // Traces over OTLP/HTTP; spans leave the pod only when the controller rendered the endpoint.
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // /actuator/prometheus, for the scraper the chart's NetworkPolicy rule admits
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // The handlers' data sources: a project jar carries only its own classes, the pod lends the driver
    implementation("org.postgresql:postgresql")
    testImplementation(testFixtures(project(":libs:plugins")))
    testImplementation(testFixtures(project(":libs:identity")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.micrometer:micrometer-tracing-test")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation(libs.archunit.junit5)
}

tasks.withType<Test>().configureEach {
    systemProperty("aisolate.docker.dir", rootProject.file("docker").absolutePath)
}

// The templates' handlers read the compose stack's Acme database as hr and clients, and the local Ollama's
// granite (the main LLM) and nomic. Your environment wins over these.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    fun dev(name: String, default: String) =
        environment(name, providers.environmentVariable(name).getOrElse(default))
    dev("AISOLATE_LLM", "granite")
    dev("AISOLATE_LLM_0_ID", "granite")
    dev("AISOLATE_LLM_0_PROVIDER", "ollama")
    dev("AISOLATE_LLM_0_URL", "http://localhost:11434")
    dev("AISOLATE_LLM_0_MODEL", "granite4.1:8b")
    dev("AISOLATE_LLM_1_ID", "nomic")
    dev("AISOLATE_LLM_1_PROVIDER", "ollama")
    dev("AISOLATE_LLM_1_URL", "http://localhost:11434")
    dev("AISOLATE_LLM_1_MODEL", "nomic-embed-text")
    dev("AISOLATE_DATASOURCE_0_ID", "hr")
    dev("AISOLATE_DATASOURCE_0_URL", "jdbc:postgresql://localhost:5433/acme")
    dev("AISOLATE_DATASOURCE_0_USERNAME", "aisolate_hr")
    dev("AISOLATE_DATASOURCE_0_PASSWORD", "aisolate_hr")
    dev("AISOLATE_DATASOURCE_1_ID", "clients")
    dev("AISOLATE_DATASOURCE_1_URL", "jdbc:postgresql://localhost:5433/acme")
    dev("AISOLATE_DATASOURCE_1_USERNAME", "aisolate_clients")
    dev("AISOLATE_DATASOURCE_1_PASSWORD", "aisolate_clients")
}

// One stable name for the image build (Dockerfile COPY).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("airlock.jar")
}
