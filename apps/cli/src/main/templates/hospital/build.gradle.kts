plugins {
    `java-library`
}

group = "com.aldermoor.ehr"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// The project compiles against the AIsolate SDK alone (libs/aisolate-sdk.jar, written by
// `aisolate create`): a tool can never reach a connection, the rules or the policy through it.
dependencies {
    implementation(files("libs/aisolate-sdk.jar"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// The rule sets ship at aisolate/rules/<entity>.json.
tasks.named<ProcessResources>("processResources") {
    from("rules") { into("aisolate/rules") }
}

// One jar, named after the template: what `aisolate deploy` sends.
tasks.named<Jar>("jar") {
    archiveBaseName.set(rootProject.name)
    archiveVersion.set("")
    manifest {
        attributes(
            "Implementation-Title" to rootProject.name,
            "Implementation-Version" to project.version,
        )
    }
}
