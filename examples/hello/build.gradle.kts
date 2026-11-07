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

// Drops the jar into the core dev server's plugins/, where Hadron picks it up within a second or two.
tasks.register<Copy>("deploy") {
    from(tasks.jar)
    into(rootProject.layout.projectDirectory.dir("neoforge/run/plugins"))
    rename { "Hello.jar" }
}

// ./gradlew :examples:hello:hadronTest runs src/test/java's @HadronTests in a headless server.
apply(from = rootProject.file("gradle/hadron-test.gradle.kts"))
