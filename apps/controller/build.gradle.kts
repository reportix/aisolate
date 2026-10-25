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

// crds/ is generated from the resource classes, never hand-edited; aisolate-chart's pin script copies it.
tasks.register<JavaExec>("syncCrds") {
    group = "build"
    description = "Generates the CRDs into crds/"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.reportix.aisolate.controller.crd.CrdGeneration")
    args(rootProject.file("crds").absolutePath)
}

// CrdGenerationTest compares crds/ with the generated CRDs; an input, so a hand edit reruns it too.
tasks.named<Test>("test") {
    inputs.dir(rootProject.file("crds")).withPropertyName("crds").withPathSensitivity(PathSensitivity.RELATIVE)
}
