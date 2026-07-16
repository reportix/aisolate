plugins {
    java
    alias(libs.plugins.spring.boot)
}

// The AIsolate controller: the Java Operator SDK's reconcilers over the three custom resources, the
// render of the pod spec's objects, server-side apply.

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":libs:config"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.fabric8.client)
    implementation(libs.fabric8.crd.generator)
    implementation(libs.josdk)
    // /actuator/prometheus, for the scraper the chart's NetworkPolicy rule admits
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.fabric8.server.mock)
}

// One stable name for the image build (Dockerfile COPY).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("controller.jar")
}

// The CRDs of chart/crds are generated from the resource classes, never hand-edited.
tasks.register<JavaExec>("syncCrds") {
    group = "build"
    description = "Generates the CRDs into chart/crds"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.reportix.aisolate.controller.crd.CrdGeneration")
    args(rootProject.file("chart/crds").absolutePath)
}
