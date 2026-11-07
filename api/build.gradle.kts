// What plugins compile against. Vanilla Minecraft only: no NeoForge or Fabric class is on the classpath, so the API
// can't mention one, and a plugin built against it runs on every loader Hadron supports.
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

version = providers.gradleProperty("hadron_version").get()
java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    // Agents read the API from the sources jar, so every release ships one.
    withSourcesJar()
}
repositories { mavenCentral() }

neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
}

dependencies {
    // Plugins' config.yml. Minecraft doesn't ship a YAML parser, so it rides inside Hadron's jar.
    api("org.yaml:snakeyaml:2.2")
}

// Fast tests: JUnit on the JVM, no server. ./gradlew :api:test. A class that needs Minecraft's registries carries
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
