// The headless test server's setup, shared by Hadron's own neoforge project and by contract bundles' tests/server, so
// the two can never drift apart. The applying project registers the two runs itself (ModDevGradle's types aren't
// visible to a script applied with apply(from = ...)), exactly like this:
//
//     register("hadronTest")  { server(); programArgument("--nogui"); disableIdeRun()
//         gameDirectory = layout.buildDirectory.dir("hadron-test/run")
//         systemProperty("hadron.test.report", layout.buildDirectory.file("hadron-test/report.txt").get().asFile.absolutePath) }
//     register("hadronWatch") { server(); programArgument("--nogui"); disableIdeRun()
//         gameDirectory = layout.buildDirectory.dir("hadron-watch/run")
//         systemProperty("hadron.test.watch", layout.buildDirectory.dir("hadron-watch").get().asFile.absolutePath) }
//
// and this script checks that they do, so a run pointed elsewhere fails the build instead of testing the wrong thing.
// Optional parameters, set before applying:
//     extra["hadronTestServer.mod"]   = the Hadron mod jar to install in mods/ (a bundle's libs/hadron.jar); unset when
//                                       the project is Hadron itself
//     extra["hadronTestServer.harness"] = the test harness mod jar to install beside it (libs/hadron-test.jar); set
//                                       exactly when hadronTestServer.mod is
//     extra["hadronTestServer.stage"] = tasks the runs must come after: (test task name) -> list of task paths

// The test server starts from nothing every run: no world, no plugins but the staged suites, nothing listening on the
// dev server's ports (the game port is any free one, RCON and query are off), and no watchdog, since a test holds the
// server thread for the whole run. Suites are moved in, not copied, so a suite staged once runs once.
object HadronTestServer {
    val PROPERTIES = """
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

    /** How many failed runs' evidence is kept. */
    const val KEEP_FAILURES = 10

    /**
     * Copies a failed run's report (text and JSON) and server log to failures/<time> in [root], where the next run
     * can't overwrite them: a failure that won't happen again on demand keeps its evidence. Returns that folder.
     */
    fun keep(root: File): File {
        val failures = root.resolve("failures")
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val dir = failures.resolve(stamp)
        dir.mkdirs()
        for (f in listOf(root.resolve("report.txt"), root.resolve("report.json"), root.resolve("run/logs/latest.log"))) {
            if (f.isFile) f.copyTo(dir.resolve(f.name), overwrite = true)
        }
        failures.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.dropLast(KEEP_FAILURES)
            ?.forEach { it.deleteRecursively() }
        return dir
    }

    fun prepare(run: File, mod: File?, harness: File?) {
        run.resolve("plugins").mkdirs()
        if ((mod == null) != (harness == null)) throw GradleException("hadronTestServer.mod and hadronTestServer.harness "
                + "go together: a test server installed from jars needs Hadron's mod and its test harness mod.")
        for ((jar, name) in listOf(mod to "hadron.jar", harness to "hadron-test.jar")) {
            if (jar == null) continue
            if (!jar.isFile) throw GradleException("Hadron's $name is missing: $jar. This folder came from Hadron's "
                    + "contractBundle task; copy the whole bundle, libs/ included.")
            val mods = run.resolve("mods")
            mods.mkdirs()
            jar.copyTo(mods.resolve(name), overwrite = true)
        }
        run.resolve("eula.txt").writeText("eula=true\n")
        run.resolve("server.properties").writeText(PROPERTIES)
    }

    /**
     * Each staged suite's extras, consumed like its jar: the mods it needs beside Hadron (`<suite>.mods/`, from its
     * `hadronTest.mods`) go to the run's mods/, and the folders it keeps between runs (`<suite>.keep`, from
     * `hadronTest.keep`, such as a mod's downloaded libraries) come back from [root]/kept, so a fresh run needs no
     * network for what an earlier one fetched.
     */
    fun stageSuiteExtras(root: File, run: File) {
        val suites = root.resolve("suites")
        suites.listFiles { f -> f.isDirectory && f.name.endsWith(".jar.mods") }?.forEach { dir ->
            dir.listFiles()?.forEach { it.copyTo(run.resolve("mods").resolve(it.name), overwrite = true) }
            dir.deleteRecursively()
        }
        val keep = suites.listFiles { f -> f.name.endsWith(".jar.keep") }.orEmpty().flatMap { f ->
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.also { f.delete() }
        }.distinct()
        run.resolve(".hadron-keep").writeText(keep.joinToString("\n", postfix = "\n"))
        for (path in keep) {
            val saved = inside(root.resolve("kept"), path)
            if (saved.isDirectory) saved.copyRecursively(inside(run, path), overwrite = true)
        }
    }

    /**
     * [base]/[path], refusing a path that leads out of [base] (absolute, `..`, or through a link), since kept folders
     * are deleted and overwritten: a bad entry must never reach files outside the test run and its cache.
     */
    fun inside(base: File, path: String): File {
        fun real(p: java.nio.file.Path): java.nio.file.Path {
            // The nearest part that exists, with its links resolved, plus whatever below it doesn't exist yet.
            val existing = generateSequence(p) { it.parent }.first { java.nio.file.Files.exists(it) }
            return existing.toRealPath().resolve(existing.relativize(p)).normalize()
        }
        val home = base.toPath().toAbsolutePath().normalize()
        val target = home.resolve(path).normalize()
        val escapes = path.isBlank() || java.nio.file.Paths.get(path).isAbsolute
            || !target.startsWith(home) || target == home
            || !real(target).startsWith(real(home)) || real(target) == real(home)
        if (escapes) {
            throw GradleException("hadronTest.keep has \"$path\", which leads out of $base. Give a relative path that "
                    + "stays inside the test server's run folder, such as config/luckperms/libs")
        }
        return target.toFile()
    }

    /** After a run, copies each kept folder back to [root]/kept for the next run. */
    fun saveKept(root: File, run: File) {
        val list = run.resolve(".hadron-keep")
        if (!list.isFile) return
        for (path in list.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val live = inside(run, path)
            if (!live.isDirectory) continue
            val saved = inside(root.resolve("kept"), path)
            saved.deleteRecursively()
            live.copyRecursively(saved, overwrite = true)
        }
    }

    /**
     * Fails unless the run passes -D[property]=[expected]. ModDevGradle writes a run's JVM arguments to a file it hands
     * the JVM as @file, so the arguments are read from there as well as from the task's own.
     */
    fun requireRun(task: JavaExec, property: String, expected: File) {
        val args = task.allJvmArgs.flatMap { arg ->
            val file = if (arg.startsWith("@")) File(arg.substring(1)) else null
            if (file != null && file.isFile) file.readLines().map { it.trim().removeSurrounding("\"") } else listOf(arg)
        } + task.systemProperties.map { (k, v) -> "-D$k=$v" }
        val value = args.lastOrNull { it.startsWith("-D$property=") }?.removePrefix("-D$property=")
        if (value == null || File(value).absoluteFile != expected.absoluteFile) {
            throw GradleException("${task.path} must run with -D$property=$expected (as gradle/hadron-test-server.gradle.kts "
                    + "shows at its top), but has ${value ?: "no such property"}: fix its run registration, or the test "
                    + "server would write its results somewhere the tests never read.")
        }
    }
}

val hadronTestDir = layout.buildDirectory.dir("hadron-test")
val hadronWatchDir = layout.buildDirectory.dir("hadron-watch")
val hadronMod: File? = (findProperty("hadronTestServer.mod") as File?)
val hadronHarness: File? = (findProperty("hadronTestServer.harness") as File?)
@Suppress("UNCHECKED_CAST")
val hadronStage = findProperty("hadronTestServer.stage") as ((String) -> List<Any>)?

tasks.named<JavaExec>("runHadronTest") {
    description = "Runs the @HadronTests of every staged suite; use a plugin's hadronTest task instead."
    // Each suite's hadronTest task reads the report and fails the build; a failed run still writes one.
    isIgnoreExitValue = true
    hadronStage?.let { stage -> mustRunAfter(provider { stage("hadronTestStage") }) }
    if (hadronMod != null) inputs.file(hadronMod)
    if (hadronHarness != null) inputs.file(hadronHarness)
    val root = hadronTestDir.get().asFile
    val mod = hadronMod
    val harness = hadronHarness
    doFirst {
        HadronTestServer.requireRun(this as JavaExec, "hadron.test.report", root.resolve("report.txt"))
        val run = root.resolve("run")
        run.deleteRecursively()
        root.resolve("report.txt").delete()
        root.resolve("report.json").delete()
        val plugins = run.resolve("plugins")
        plugins.mkdirs()
        // Each jar with its --tests filter, if it has one.
        root.resolve("suites").listFiles { f -> f.name.endsWith(".jar") || f.name.endsWith(".jar.tests") }?.forEach { f ->
            f.copyTo(plugins.resolve(f.name), overwrite = true)
            f.delete()
        }
        HadronTestServer.prepare(run, mod, harness)
        HadronTestServer.stageSuiteExtras(root, run)
    }
    // The server exits 0 only when every test passed. A failing run's report already says why, so hadronTest reports
    // it; but a nonzero exit after a passing report means the server broke after the tests (a crash while stopping,
    // a mod failing at shutdown), and trusting the report alone would end that green.
    doLast {
        HadronTestServer.saveKept(root, root.resolve("run"))
        val code = (this as JavaExec).executionResult.get().exitValue
        // The server exits 0 only when every test passed, so any other exit is a failed run: keep its evidence.
        val kept = if (code != 0) HadronTestServer.keep(root) else null
        val report = root.resolve("report.txt")
        val passed = report.isFile && report.readLines().lastOrNull { it.startsWith("RESULT:") }?.trim() == "RESULT: PASSED"
        if (code != 0 && passed) {
            throw GradleException("The test server reported every test passed, then exited with code $code, so "
                    + "something broke after the tests ran. Its log says what: " + root.resolve("run/logs/latest.log")
                    + " (kept in $kept)")
        }
    }
}

// The watching test server starts as fresh as the one-off one, keeping only the inbox, where hadronWatch has just
// staged a suite. It runs until hadronWatchStop or Ctrl+C.
tasks.named<JavaExec>("runHadronWatch") {
    description = "Starts a test server that reruns each staged suite; use a plugin's hadronWatch task instead."
    isIgnoreExitValue = true
    hadronStage?.let { stage -> mustRunAfter(provider { stage("hadronWatchStage") }) }
    if (hadronMod != null) inputs.file(hadronMod)
    if (hadronHarness != null) inputs.file(hadronHarness)
    val root = hadronWatchDir.get().asFile
    val mod = hadronMod
    val harness = hadronHarness
    doFirst {
        HadronTestServer.requireRun(this as JavaExec, "hadron.test.watch", root)
        val state = root.resolve("watch.properties")
        if (state.isFile) {
            val pid = state.readLines().firstOrNull { it.startsWith("pid=") }?.removePrefix("pid=")?.toLongOrNull()
            if (pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
                throw GradleException("A Hadron test server is already watching (pid $pid). Rerun tests on it with "
                        + "hadronRerun, or stop it with hadronWatchStop first.")
            }
        }
        for (name in listOf("run", "done", "reports", "report.txt", "report.json", "watch.properties", "stop",
                "last-run.properties", "hadron.fingerprint")) {
            root.resolve(name).deleteRecursively()
        }
        // The record of the Hadron this server loads, which hadronWatch wrote after building it; reruns compare it.
        root.resolve("hadron.fingerprint.pending").renameTo(root.resolve("hadron.fingerprint"))
        HadronTestServer.prepare(root.resolve("run"), mod, harness)
    }
    // It exits 0 only when hadronWatchStop asked it to stop, so anything else (a crash, a hung test, a test that
    // stopped the server) fails the build rather than ending it green.
    doLast {
        val code = (this as JavaExec).executionResult.get().exitValue
        if (code != 0) {
            val why = if (code == 3) "a test hung, so the watchdog ended it"
                else "it crashed or was stopped by something other than hadronWatchStop"
            throw GradleException("The watching test server exited with code $code: $why. Its log says more: "
                    + root.resolve("run/logs/latest.log") + ". Start hadronWatch again.")
        }
    }
}
