// Hadron's own tests: a plugin that does nothing, whose src/test/java checks Hadron's events and platform APIs on a
// real server with the harness plugin authors use. ./gradlew :selftest:hadronTest
plugins {
    java
    id("net.neoforged.moddev") version "2.0.148"
}

version = providers.gradleProperty("hadron_version").get()
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
repositories { mavenCentral() }

neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
    addModdingDependenciesTo(sourceSets.test.get())
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":build-checks"))
    testCompileOnly(project(":api"))
    testCompileOnly(project(":test-api"))
    // @Nondeterministic, for the self-tests that test waits on other threads; Hadron's code only.
    testCompileOnly(project(":build-checks"))
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft" to providers.gradleProperty("minecraft_version").get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// src/test holds only in-game tests, which hadronTest runs on a server; their Hadron types are compile-only here,
// so the JVM can't load them. Hadron's fast tests live in api's and common's src/test.
tasks.test { enabled = false }

apply(from = rootProject.file("gradle/hadron-test.gradle.kts"))

// coverageCheck (part of check): every shipped event and public API method needs a test that says it covers it with
// @Covers. Reads Hadron's api classes and these tests, plus api's and common's own JUnit tests, without a server.
extra["hadronCoverage.api"] = project(":api").layout.buildDirectory.dir("classes/java/main")
extra["hadronCoverage.tests"] = listOf(
    layout.buildDirectory.dir("classes/java/test"),
    project(":api").layout.buildDirectory.dir("classes/java/test"),
    project(":common").layout.buildDirectory.dir("classes/java/test"),
)
extra["hadronCoverage.dependsOn"] = listOf(":api:classes", "testClasses", ":api:testClasses", ":common:testClasses")
apply(from = rootProject.file("gradle/hadron-coverage.gradle.kts"))

// Compile-time checks every plugin build runs (gradle/hadron-checks.gradle.kts): Error Prone's errors, no static
// mutable state, and deterministic tests. Hadron's own tests may exempt a wait on another thread, with a reason.
extra["hadronChecks.exemptions"] = true
apply(from = rootProject.file("gradle/hadron-checks.gradle.kts"))
