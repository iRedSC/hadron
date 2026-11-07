// The plugin host, shared by every loader. Vanilla Minecraft only; what a loader must supply comes through Platform.
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

version = providers.gradleProperty("hadron_version").get()
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
repositories { mavenCentral() }

neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
}

dependencies {
    api(project(":api"))
}

// Fast tests: JUnit on the JVM, no server. ./gradlew :common:test. A class that needs Minecraft's registries carries
// @WithMinecraft from test-api; the rest don't boot Minecraft at all.
neoForge.addModdingDependenciesTo(sourceSets.test.get())

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":test-api"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
