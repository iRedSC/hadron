// Hadron's benchmarks: a plugin that does nothing, whose src/test/java times Hadron's hot paths on a real server with
// the harness plugin authors use. ./gradlew :bench:chokepointBench
//
// Not part of check or build: a timing says how fast this machine was, not whether Hadron is right, and the machine
// running a build may be busy. Each bench still fails when the path it timed isn't the one it meant to (an event that
// should have fired didn't), so a number never comes from the wrong code.
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
    // @Nondeterministic, for reading the clock to time things; Hadron's code only.
    testCompileOnly(project(":build-checks"))
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft" to providers.gradleProperty("minecraft_version").get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// src/test holds only in-game benches, which hadronTest runs on a server.
tasks.test { enabled = false }

apply(from = rootProject.file("gradle/hadron-test.gradle.kts"))

extra["hadronChecks.exemptions"] = true
apply(from = rootProject.file("gradle/hadron-checks.gradle.kts"))

// Each bench writes its table into the server's folder; this keeps it beside the build and prints it.
tasks.register("chokepointBench") {
    group = "verification"
    description = "Times the setBlock chokepoint on a headless server and prints what each case costs per call."
    dependsOn("hadronTest")
    // Locals, not script-level vals: doLast reading those would capture the script, which the configuration cache
    // refuses.
    val benchResults = rootProject.file("neoforge/build/hadron-test/run/bench")
    val benchKept = layout.buildDirectory.dir("bench").get().asFile
    doLast {
        val table = benchResults.resolve("chokepoint.txt")
        if (!table.isFile) {
            throw GradleException("The bench passed but wrote no table at $table. ChokepointBench writes it last; "
                    + "check that it still does.")
        }
        benchKept.mkdirs()
        val kept = table.copyTo(benchKept.resolve(table.name), overwrite = true)
        println("\n" + kept.readText() + "\nKept in $kept")
    }
}
