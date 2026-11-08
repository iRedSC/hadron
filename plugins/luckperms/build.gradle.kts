// The LuckPerms provider: a bundled write-once plugin that answers Hadron's Permissions from LuckPerms, through
// LuckPerms' pure-Java API (api-depend: [luckperms] in plugin.yml), so it runs on every loader LuckPerms does.
plugins {
    java
    id("net.neoforged.moddev") version "2.0.148"
}

version = providers.gradleProperty("hadron_version").get()
java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    mavenCentral()
    // The LuckPerms mod itself, only for the test server.
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
    addModdingDependenciesTo(sourceSets.test.get())
}

// The mods the test server loads beside Hadron. 5.4.140, the build labelled for NeoForge 1.21.1, can't let anyone join
// (LuckPerms issues #4048 and #4235); 5.4.150, labelled 1.21.4, runs on 1.21.1 and is what owners are told to use.
val testMods: Configuration by configurations.creating { isTransitive = false }

dependencies {
    compileOnly(project(":api"))
    // The API LuckPerms 5.4.x ships; the mod provides it at runtime.
    compileOnly("net.luckperms:api:5.4")
    testCompileOnly(project(":api"))
    testCompileOnly(project(":test-api"))
    testCompileOnly("net.luckperms:api:5.4")
    testMods("maven.modrinth:luckperms:v5.4.150-neoforge")
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft" to providers.gradleProperty("minecraft_version").get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// In-game tests only, against a real LuckPerms; nothing here runs on the bare JVM.
tasks.test { enabled = false }

extra["hadronTest.mods"] = testMods
// LuckPerms downloads its storage drivers on first start; keep them across runs so tests don't need the network.
extra["hadronTest.keep"] = listOf("config/luckperms/libs")

apply(from = rootProject.file("gradle/hadron-test.gradle.kts"))
apply(from = rootProject.file("gradle/hadron-checks.gradle.kts"))
