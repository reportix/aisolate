plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":libs:config"))
    implementation(project(":libs:identity"))
    implementation(project(":libs:plugins"))
    implementation(project(":libs:sdk"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.springdoc.openapi)
    implementation(libs.fabric8.client)
    // A Python artifact's manifest, checked against docs/python-artifact.schema.json
    implementation(libs.json.schema.validator)
    runtimeOnly("org.postgresql:postgresql")
    // /actuator/prometheus, for the scraper the chart's NetworkPolicy rule admits
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation(testFixtures(project(":libs:plugins")))
    testImplementation(testFixtures(project(":libs:identity")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.fabric8.server.mock)
}

tasks.withType<Test>().configureEach {
    systemProperty("aisolate.docker.dir", rootProject.file("docker").absolutePath)
}

// The 50 MB artifact in a JVM of its own whose heap cannot hold it twice (a fetch buffered whole
// fails it with an OutOfMemoryError), so it proves the streaming.
val largeArtifactTest by tasks.registering(Test::class) {
    description = "Deploys and fetches a 50 MB artifact in a small heap."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("large-artifact") }
    maxHeapSize = "128m"
}
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("large-artifact") } }
tasks.named("check") { dependsOn(largeArtifactTest) }

// The manifest's schema has one home, docs/: the deploy reads that file.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("docs/python-artifact.schema.json"))
}

// One stable name for the image build (Dockerfile COPY).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("backend.jar")
}
