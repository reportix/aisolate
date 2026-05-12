plugins {
    `java-library`
}

// The document schema and loader both runtimes share. Jackson only: YAML in, YAML out, JSON nodes for
// the rule grammar, which each runtime keeps parsing itself.
dependencies {
    api(platform(libs.spring.boot.bom))
    api("tools.jackson.core:jackson-databind")
    api("tools.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core")
}
