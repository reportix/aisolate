plugins {
    `java-library`
}

// The AIsolate Audit xBRL-XML report, written by the backend's audit export. Jackson reads the audit
// lines; the report is written with the JDK alone.
dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")

    // The airlock's own "I don't know" rule, which the report's copy must match.
    testImplementation(project(":libs:config"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core")
}
