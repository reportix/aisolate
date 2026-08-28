pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisions JDK 25 on machines/CI runners that do not have it installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "aisolate"

include(
    "apps:airlock", "apps:backend", "apps:controller", "apps:tool-server",
    "libs:audit-report", "libs:config", "libs:identity", "libs:plugins", "libs:sdk",
)
