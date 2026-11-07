pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hadron"

// api: what plugins compile against. Vanilla Minecraft only, so a plugin can't touch a loader's classes.
// common: the plugin host itself, also vanilla only, shared by every loader.
// neoforge: the NeoForge port. The only part rewritten for another loader.
include("api", "common", "neoforge")

// test-api: what a plugin's tests compile against (@HadronTest, TestContext, TestPlayer), vanilla only like api.
include("test-api")

// A plugin built the way plugin authors will, used to try the host on a real server.
include("examples:hello")

// Hadron's own tests: a plugin whose src/test/java checks Hadron's events and APIs with the harness plugin authors use.
include("selftest")
