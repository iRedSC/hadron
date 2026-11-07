// The headless test server that runs the tests' @HadronTests: NeoForge with the real Hadron mod (../libs/hadron.jar)
// in its mods folder, as a server owner installs it. Started by the root project's hadronTest and hadronWatch; don't
// run these tasks directly.
plugins {
    java
    id("net.neoforged.moddev")
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
repositories { mavenCentral() }

val hadronMod = rootProject.layout.projectDirectory.file("libs/hadron.jar").asFile

neoForge {
    version = providers.gradleProperty("neo_version").get()
    runs {
        // Test mode: a fresh server in build/hadron-test/run that runs the staged suite's @HadronTests and stops.
        register("hadronTest") {
            server()
            programArgument("--nogui")
            gameDirectory = layout.buildDirectory.dir("hadron-test/run")
            systemProperty("hadron.test.report", layout.buildDirectory.file("hadron-test/report.txt").get().asFile.absolutePath)
            disableIdeRun()
        }
        // Watch mode: the same server, staying up to rerun each build staged in build/hadron-watch/inbox.
        register("hadronWatch") {
            server()
            programArgument("--nogui")
            gameDirectory = layout.buildDirectory.dir("hadron-watch/run")
            systemProperty("hadron.test.watch", layout.buildDirectory.dir("hadron-watch").get().asFile.absolutePath)
            disableIdeRun()
        }
    }
}

// The test server's setup is Hadron's own (gradle/hadron-test-server.gradle.kts, copied into this bundle), so this
// server can't drift from the one Hadron tests itself with.
extra["hadronTestServer.mod"] = hadronMod
extra["hadronTestServer.stage"] = { stage: String -> listOf(":$stage") }
apply(from = rootProject.file("gradle/hadron-test-server.gradle.kts"))
