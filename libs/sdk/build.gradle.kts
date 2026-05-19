plugins {
    `java-library`
}

// No dependencies on purpose: a plugin compiles against the SDK and the JDK alone, so it can never
// reach the rules or the policy through it, and a database only by the id of a data source.
// Only the tests have any, and none of them reaches the jar.
dependencies {
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.assertj:assertj-core")
}

// Customers vendor the jar (the CLI ships it as libs/aisolate-sdk.jar): a fixed module name lets a
// modular build require it.
tasks.jar { manifest { attributes("Automatic-Module-Name" to "com.reportix.aisolate.sdk") } }
