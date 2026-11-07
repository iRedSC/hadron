pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "contract-tests"

// This folder is the tests: a plugin that holds nothing but them, compiled against libs/hadron-api.jar and
// libs/hadron-test-api.jar. server: the headless test server, booting the real Hadron mod from libs/hadron.jar.
include("server")
