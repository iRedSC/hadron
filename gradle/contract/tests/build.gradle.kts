// Blind tests of Hadron's contract. Written from ../contract only; compiled and run against the real Hadron, as
// binary jars in libs/. See ../README.md.
//
//     ./gradlew test            fast tests (JUnit @Test), no server
//     ./gradlew hadronTest      in-game tests (@HadronTest) in a fresh headless server
//     ./gradlew coverageCheck   lists every event and API method no test here says it @Covers
plugins {
    java
    id("net.neoforged.moddev") version "2.0.148"
}

version = "1.0.0"
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
repositories { mavenCentral() }

// Vanilla Minecraft only, as every plugin builds: a NeoForge class in a test doesn't compile.
neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
}
neoForge.addModdingDependenciesTo(sourceSets.test.get())

val hadronApi = files("libs/hadron-api.jar")
val hadronTestApi = files("libs/hadron-test-api.jar")

dependencies {
    compileOnly(hadronApi)
    testCompileOnly(hadronApi)
    testCompileOnly(hadronTestApi)
    // Hadron's API reads YAML with it, and ships it; tests may use it too.
    testImplementation("org.yaml:snakeyaml:2.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(hadronApi)
    testRuntimeOnly(hadronTestApi)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // Machine-readable results: build/test-results/test/*.xml.
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft" to providers.gradleProperty("minecraft_version").get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// hadronTest, hadronWatch, hadronRerun, hadronWatchStop: Hadron's own test runner, as plugins get it.
apply(from = "gradle/hadron-test.gradle.kts")

// coverageCheck: reads the api jar's classes and these tests' @Covers, without a server.
val hadronApiClasses = tasks.register<Sync>("hadronApiClasses") {
    from(zipTree("libs/hadron-api.jar")) { include("**/*.class") }
    into(layout.buildDirectory.dir("hadron-api-classes"))
}
extra["hadronCoverage.api"] = hadronApiClasses.map { it.destinationDir }
extra["hadronCoverage.tests"] = listOf(layout.buildDirectory.dir("classes/java/test"))
extra["hadronCoverage.dependsOn"] = listOf(hadronApiClasses, "testClasses")
apply(from = "gradle/hadron-coverage.gradle.kts")
