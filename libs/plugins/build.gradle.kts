plugins {
    `java-library`
    `java-test-fixtures`
}

// Reading a developer's jar: the tool plugins and handlers it declares, in any package, and the
// loader both the backend (to list them) and the pods (to run them) use. The test fixtures build
// such jars from source at test time.
dependencies {
    api(project(":libs:sdk"))
    api(project(":libs:config"))
    api(platform(libs.spring.boot.bom))
    implementation("org.slf4j:slf4j-api")

    testFixturesApi(project(":libs:sdk"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core")
    // A provider, so a test run does not print SLF4J's "no providers" warning; the pods bring theirs.
    testRuntimeOnly("org.slf4j:slf4j-nop")
}
