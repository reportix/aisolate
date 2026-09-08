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

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}

tasks.test {
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

graalvmNative {
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            imageName.set("aisolate-runner")
            mainClass.set("com.reportix.aisolate.cli.AIsolateCli")
            sharedLibrary.set(false)
            buildArgs.add("-J-Xmx4g")
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
