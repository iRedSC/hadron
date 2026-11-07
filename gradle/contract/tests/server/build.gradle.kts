// The headless test server that runs the tests' @HadronTests: NeoForge with the real Hadron mod (../libs/hadron.jar)
// in its mods folder, as a server owner installs it. Started by the root project's hadronTest and hadronWatch; don't
// run these tasks directly. Mirrors Hadron's own test server settings.
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

val testServerProperties = """
    online-mode=false
    server-ip=127.0.0.1
    server-port=0
    enable-rcon=false
    enable-query=false
    level-type=minecraft\:flat
    generate-structures=false
    spawn-protection=0
    max-tick-time=-1
    view-distance=4
    simulation-distance=4
    enforce-secure-profile=false
    sync-chunk-writes=false
    max-players=100
    """.trimIndent() + "\n"

// An object, not a script function: the configuration cache can't keep a task action that calls into the script.
object HadronInstall {
    /** Puts Hadron in a fresh run directory's mods folder, as a server owner installs it. */
    fun into(run: File, mod: File) {
        if (!mod.isFile) throw GradleException("Hadron's mod jar is missing: $mod. This folder came from Hadron's "
                + "contractBundle task; copy the whole bundle, libs/ included.")
        val mods = run.resolve("mods")
        mods.mkdirs()
        mod.copyTo(mods.resolve("hadron.jar"), overwrite = true)
    }
}

val hadronTestDir = layout.buildDirectory.dir("hadron-test")
val hadronWatchDir = layout.buildDirectory.dir("hadron-watch")

tasks.named<JavaExec>("runHadronTest") {
    description = "Runs the staged suite's @HadronTests; use the root project's hadronTest instead."
    // hadronTest reads the report and fails the build; a failed run still writes one.
    isIgnoreExitValue = true
    mustRunAfter(":hadronTestStage")
    inputs.file(hadronMod)
    val root = hadronTestDir.get().asFile
    val properties = testServerProperties
    val mod = hadronMod
    doFirst {
        val run = root.resolve("run")
        run.deleteRecursively()
        root.resolve("report.txt").delete()
        root.resolve("report.json").delete()
        val plugins = run.resolve("plugins")
        plugins.mkdirs()
        // Each jar with its --tests filter, if it has one; moved, so a suite staged once runs once.
        root.resolve("suites").listFiles { f -> f.name.endsWith(".jar") || f.name.endsWith(".jar.tests") }?.forEach { f ->
            f.copyTo(plugins.resolve(f.name), overwrite = true)
            f.delete()
        }
        HadronInstall.into(run, mod)
        run.resolve("eula.txt").writeText("eula=true\n")
        run.resolve("server.properties").writeText(properties)
    }
}

tasks.named<JavaExec>("runHadronWatch") {
    description = "Starts a test server that reruns each staged build; use the root project's hadronWatch instead."
    isIgnoreExitValue = true
    mustRunAfter(":hadronWatchStage")
    val root = hadronWatchDir.get().asFile
    val properties = testServerProperties
    val mod = hadronMod
    doFirst {
        val state = root.resolve("watch.properties")
        if (state.isFile) {
            val pid = state.readLines().firstOrNull { it.startsWith("pid=") }?.removePrefix("pid=")?.toLongOrNull()
            if (pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
                throw GradleException("A Hadron test server is already watching (pid $pid). Rerun tests on it with "
                        + "hadronRerun, or stop it with hadronWatchStop first.")
            }
        }
        for (name in listOf("run", "done", "reports", "report.txt", "report.json", "watch.properties", "stop")) {
            root.resolve(name).deleteRecursively()
        }
        val run = root.resolve("run")
        run.resolve("plugins").mkdirs()
        HadronInstall.into(run, mod)
        run.resolve("eula.txt").writeText("eula=true\n")
        run.resolve("server.properties").writeText(properties)
    }
}
