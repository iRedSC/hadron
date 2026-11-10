plugins {
    java
    id("net.neoforged.moddev") version "2.0.148"
}

version = "1.0.0"
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
repositories { mavenCentral() }

// Vanilla Minecraft and Hadron's API only, as every portable plugin builds: a NeoForge class here won't compile.
neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
}

// Tests (src/test/java) see Minecraft, the plugin, Hadron's api and test-api; the plugin's own code never sees test-api.
neoForge.addModdingDependenciesTo(sourceSets.test.get())

dependencies {
    compileOnly(project(":api"))
    testCompileOnly(project(":api"))
    testCompileOnly(project(":test-api"))

    // Fast tests: plain JUnit in the same src/test/java, run by ./gradlew test on the JVM with no server.
    // @WithMinecraft (test-api) boots Minecraft's registries for the classes that need them.
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(project(":api"))
    testRuntimeOnly(project(":test-api"))
}

// ./gradlew :examples:hello:test runs the fast tests (@Test); the in-game @HadronTests run only with hadronTest.
tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft" to providers.gradleProperty("minecraft_version").get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// ./gradlew :examples:hello:deploy drops the jar into the running dev server (./gradlew :neoforge:runServer), which
// reloads it within a second or two, and prints whether this build is enabled or why it failed. The build fails if
// it didn't load (gradle/hadron-deploy.gradle.kts).
extra["hadronDeploy.installAs"] = "Hello.jar"
apply(from = rootProject.file("gradle/hadron-deploy.gradle.kts"))

// ./gradlew :examples:hello:hadronTest runs src/test/java's @HadronTests in a headless server.
apply(from = rootProject.file("gradle/hadron-test.gradle.kts"))

// Compile-time checks every plugin build runs (gradle/hadron-checks.gradle.kts): Error Prone's errors, no static
// mutable state, and deterministic tests.
apply(from = rootProject.file("gradle/hadron-checks.gradle.kts"))
