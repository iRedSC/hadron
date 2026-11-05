pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hadron"

// core: the NeoForge mod. Plugins build against its public dev.iredsc.hadron.api package.
include("core")

// A plugin built the way plugin authors will, used to try the host on a real server.
include("examples:hello")
