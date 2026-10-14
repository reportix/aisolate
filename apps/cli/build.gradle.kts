import javax.inject.Inject
import org.gradle.process.ExecOperations

// Configuration-cache-safe exec for the installer tasks.
interface InstallerExecOps {
    @get:Inject
    val execOps: ExecOperations
}

val installerExecOps = objects.newInstance<InstallerExecOps>()

plugins {
    application
    alias(libs.plugins.graalvm.native)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")
    implementation(libs.picocli)
    annotationProcessor(libs.picocli.codegen)

    // The template build test reads the generated project like the backend would.
    testImplementation(project(":libs:config"))
    testImplementation(project(":libs:sdk"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

// The templates' Java is the sample code customers start from: compiled and tested against this
// SDK in every build, offline, so an SDK change that breaks it fails here and not in a new project.
// Their packages differ, so all templates share one source set.
val templates: SourceSet = sourceSets.create("templates") {
    java.srcDirs(
        listOf("company-hr", "company-sales", "auditor", "hospital").flatMap { template ->
            listOf("main", "test").map { "src/main/templates/$template/src/$it/java" }
        },
    )
}
dependencies {
    "templatesImplementation"(project(":libs:sdk"))
    "templatesImplementation"(platform(libs.spring.boot.bom))
    "templatesImplementation"("org.junit.jupiter:junit-jupiter")
    "templatesImplementation"("org.assertj:assertj-core")
    "templatesRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
val templateTest = tasks.register<Test>("templateTest") {
    description = "Runs the templates' own tests against this checkout's SDK."
    group = "verification"
    testClassesDirs = templates.output.classesDirs
    classpath = templates.runtimeClasspath
}

// TemplateBuildTest's build of a created project is opt-in (network): -Daisolate.template.build=true.
tasks.test {
    // `test` is what CI runs.
    dependsOn(templateTest)
    systemProperty("aisolate.template.build", System.getProperty("aisolate.template.build", "false"))
    // TemplateBuildTest loads the templates' plugins from here, apart from its own classpath.
    inputs.files(templates.output.classesDirs)
    systemProperty("aisolate.templates.classes", templates.output.classesDirs.asPath)
    // The tests pin their endpoint and space in the project's .env; a developer's shell must not
    // override them.
    environment = environment.filterKeys { it != "AISOLATE_ENDPOINT" && it != "AISOLATE_SPACE" }
}

application {
    mainClass.set("com.reportix.aisolate.cli.AIsolateCli")
    applicationName = "aisolate"
}

// The version the binary prints, generated from the Gradle project version.
val generatedVersionDir = layout.buildDirectory.dir("generated/sources/version")
sourceSets["main"].java.srcDir(generatedVersionDir)
tasks.register("generateVersionConstants") {
    val outDir = generatedVersionDir
    val ver = project.version.toString()
    inputs.property("version", ver)
    outputs.dir(outDir)
    doLast {
        val pkg = outDir.get().dir("com/reportix/aisolate/cli").asFile
        pkg.mkdirs()
        pkg.resolve("VersionConstants.java").writeText(
            """package com.reportix.aisolate.cli;

/** Generated at build time from the Gradle project version. Do not edit. */
public final class VersionConstants {
  public static final String VERSION = "$ver";

  private VersionConstants() {}
}
"""
        )
    }
}
tasks.named("compileJava") { dependsOn("generateVersionConstants") }

// The templates a `create` can extract, zipped from src/main/templates/<name>/**, and the SDK jar a
// project compiles against, both generated resources so a fresh checkout builds them.
val generatedResources = layout.buildDirectory.dir("generated-resources")
sourceSets["main"].resources.srcDir(generatedResources)
tasks.register<Zip>("createTemplateZip") {
    from(layout.projectDirectory.dir("src/main/templates"))
    includeEmptyDirs = true
    destinationDirectory.set(generatedResources)
    archiveFileName.set("templates.zip")
    setMetadataCharset("UTF-8")
}
// The Python templates, from src/main/templates-python/<name>/**, without what their test runs leave.
val pythonLeftovers =
    listOf("**/__pycache__/**", "**/*.pyc", "**/.pytest_cache/**", "**/.venv/**", "**/build/**", "**/libs/aisolate-sdk/**", "**/uv.lock")
tasks.register<Zip>("createPythonTemplateZip") {
    from(layout.projectDirectory.dir("src/main/templates-python")) {
        exclude(pythonLeftovers)
        exclude("test_same_as_java.py")
    }
    includeEmptyDirs = true
    destinationDirectory.set(generatedResources)
    archiveFileName.set("templates-python.zip")
    setMetadataCharset("UTF-8")
}
// The Python SDK a project's pyproject points to (libs/aisolate-sdk): its pyproject and package only.
tasks.register<Zip>("createPythonSdkZip") {
    from(rootProject.file("libs/sdk-python")) {
        include("pyproject.toml", "src/aisolate/**")
        exclude(pythonLeftovers)
        into("aisolate-sdk")
    }
    destinationDirectory.set(generatedResources)
    archiveFileName.set("aisolate-sdk-python.zip")
}
// The bundled SDK jar is what a project compiles against.
tasks.register<Copy>("copySdkJar") {
    from(project(":libs:sdk").tasks.named("jar"))
    into(generatedResources.map { it.dir("sdk") })
    rename { "aisolate-sdk.jar" }
}
tasks.named("processResources") {
    dependsOn("createTemplateZip", "createPythonTemplateZip", "createPythonSdkZip", "copySdkJar")
}

graalvmNative {
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            imageName.set("aisolate-runner")
            mainClass.set("com.reportix.aisolate.cli.AIsolateCli")
            sharedLibrary.set(false)
            buildArgs.add("-J-Xmx4g")
            buildArgs.add("-H:IncludeResources=templates.zip|templates-python.zip|aisolate-sdk-python.zip|sdk/.*")
        }
    }
}

tasks.register("renameRunnerMacos") {
    dependsOn("nativeCompile")
    doLast {
        val runner = layout.buildDirectory.file("native/nativeCompile/aisolate-runner").get().asFile
        if (!runner.exists()) throw GradleException("native image not found at $runner")
        copy { from(runner); into(layout.buildDirectory); rename { "aisolate" } }
    }
}

val pkgVersion = project.version.toString().removeSuffix("-SNAPSHOT")
tasks.register("createMacosInstaller") {
    group = "distribution"
    description = "Creates the macOS .pkg installer (/usr/local/bin/aisolate)"
    dependsOn("renameRunnerMacos")
    doLast {
        val payloadDir = layout.buildDirectory.dir("macos-payload/usr/local/bin").get().asFile
        payloadDir.mkdirs()
        copy { from(layout.buildDirectory.file("aisolate")); into(payloadDir) }
        payloadDir.resolve("aisolate").setExecutable(true, false)
        val resourcesDir = layout.buildDirectory.dir("installer-resources").get().asFile
        resourcesDir.mkdirs()
        copy { from("$projectDir/installer") { include("LICENSE") }; into(resourcesDir) }
        copy { from("$projectDir/installer/macos") { include("welcome.html", "conclusion.html") }; into(resourcesDir) }
        installerExecOps.execOps.exec {
            commandLine(
                "pkgbuild",
                "--root", layout.buildDirectory.dir("macos-payload").get().asFile.absolutePath,
                "--identifier", "com.reportix.aisolate.cli",
                "--version", pkgVersion,
                "--install-location", "/",
                layout.buildDirectory.file("aisolate-component.pkg").get().asFile.absolutePath
            )
        }
        installerExecOps.execOps.exec {
            commandLine(
                "productbuild",
                "--distribution", "$projectDir/installer/macos/aisolate-cli-installer-distribution.xml",
                "--resources", resourcesDir.absolutePath,
                "--package-path", layout.buildDirectory.get().asFile.absolutePath,
                layout.buildDirectory.file("aisolate-cli-installer.pkg").get().asFile.absolutePath
            )
        }
    }
}

tasks.register("renameRunnerWindows") {
    dependsOn("nativeCompile")
    doLast {
        val runner = layout.buildDirectory.file("native/nativeCompile/aisolate-runner.exe").get().asFile
        if (!runner.exists()) throw GradleException("native image not found at $runner")
        copy { from(runner); into(layout.buildDirectory); rename { "aisolate.exe" } }
    }
}

// -PwindowsExe=<path>: package an exe built on a Windows machine, wherever makensis runs.
val prebuiltWindowsExe = providers.gradleProperty("windowsExe").orNull?.let { file(it).absolutePath }

tasks.register("createWindowsInstaller") {
    group = "distribution"
    description = "Creates the Windows NSIS installer (.exe); needs makensis on PATH"
    if (prebuiltWindowsExe == null) dependsOn("renameRunnerWindows")
    val exe = prebuiltWindowsExe ?: layout.buildDirectory.file("aisolate.exe").get().asFile.absolutePath
    doLast {
        val workDir = layout.buildDirectory.dir("win-installer").get().asFile
        workDir.mkdirs()
        copy { from("$projectDir/installer/windows") { include("aisolate-cli-installer.nsi") }; from("$projectDir/installer") { include("LICENSE") }; into(workDir) }
        installerExecOps.execOps.exec {
            workingDir = workDir
            commandLine("makensis", "-DAISOLATE_EXE_PATH=$exe", "aisolate-cli-installer.nsi")
        }
        copy { from(workDir.resolve("aisolate-cli-installer.exe")); into(layout.buildDirectory) }
    }
}
