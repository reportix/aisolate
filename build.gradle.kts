import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    // check and build at the root, so pythonTest runs with them
    base
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.lombok) apply false
}

allprojects {
    group = "com.reportix.aisolate"
    version = "0.1.0-SNAPSHOT"
}

// Typed catalog accessors are not available inside `subprojects {}`; capture at root scope.
val javaVersion = libs.versions.java.get().toInt()

// Modules only: :apps and :libs are folders, not projects with code.
configure(subprojects.filter { it.childProjects.isEmpty() }) {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "io.freefair.lombok")

    repositories {
        mavenCentral()
    }

    // Reproducible builds and a lockfile Trivy can scan; refresh with `./gradlew dependencies --write-locks`.
    dependencyLocking {
        lockAllConfigurations()
    }

    // CVE floors above what the Spring Boot BOM manages. Remove each once the BOM catches up.
    dependencies {
        // CVE-2026-68497, CVE-2026-89407, CVE-2026-89425, CVE-2026-91776, CVE-2026-91777 (Trivy);
        // test fixtures resolve apart from implementation
        val jacksonBoms = listOf("com.fasterxml.jackson:jackson-bom:2.22.4", "tools.jackson:jackson-bom:3.2.4")
        jacksonBoms.forEach { "implementation"(platform(it)) }
        plugins.withId("java-test-fixtures") { jacksonBoms.forEach { "testFixturesImplementation"(platform(it)) } }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    // Gradle 9 needs the JUnit launcher on the test classpath; each module's BOM pins its version.
    dependencies { "testRuntimeOnly"("org.junit.platform:junit-platform-launcher") }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("spring.profiles.active", "test")
        // The language-neutral cases both runtimes pass; an input so a changed case reruns the tests
        val conformance = rootProject.file("conformance/cases")
        inputs.dir(conformance).withPropertyName("conformanceCases").withPathSensitivity(PathSensitivity.RELATIVE)
        systemProperty("aisolate.conformance.dir", conformance.absolutePath)
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat().reflowLongStrings()
            removeUnusedImports()
        }
        kotlinGradle {
            target("*.gradle.kts")
        }
    }
}

// The Python suites: each module's pytest under its own uv.lock.
// A module without tests/ yet is skipped; a failing test fails the build.
interface PythonExecOps {
    @get:javax.inject.Inject
    val execOps: org.gradle.process.ExecOperations
}
val pythonExec = objects.newInstance<PythonExecOps>()
val pythonTest = tasks.register("pythonTest") {
    group = "verification"
    description = "Runs the Python modules' tests with uv."
    val root = rootDir
    doLast {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        if (path.none { File(it, "uv").canExecute() || File(it, "uv.exe").canExecute() }) {
            throw GradleException("pythonTest needs uv on the PATH: https://docs.astral.sh/uv/getting-started/installation/")
        }
        fun pytest(project: String, vararg args: String) {
            // --frozen with a lock: the build never rewrites one
            val frozen = if (File(root, "$project/uv.lock").isFile) listOf("--frozen") else listOf()
            logger.lifecycle("pytest ${args.joinToString(" ").ifEmpty { project }}")
            pythonExec.execOps.exec {
                workingDir = if (args.isEmpty()) File(root, project) else root
                commandLine(listOf("uv", "run", "--project", File(root, project).path) + frozen + "pytest" + args)
            }
        }
        for (module in listOf("libs/sdk-python", "libs/runtime-python", "apps/tool-server-python")) {
            if (File(root, "$module/tests").isDirectory) pytest(module) else logger.lifecycle("pytest $module: no tests yet, skipped")
        }
    }
}
tasks.named("check") { dependsOn(pythonTest) }
