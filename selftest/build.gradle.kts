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
    testCompileOnly(project(":api"))
    testCompileOnly(project(":test-api"))
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft" to providers.gradleProperty("minecraft_version").get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

apply(from = rootProject.file("gradle/hadron-test.gradle.kts"))
