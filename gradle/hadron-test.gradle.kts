// hadronTest for a plugin project: builds the plugin with its src/test/java classes into one jar, stages it for
// :neoforge:runHadronTest (a headless server that runs every @HadronTest and writes a report), then fails the build
// unless every one of this plugin's tests passed. Applied with apply(from = ...) for now; the Hadron Gradle plugin
// will own it, which is why it uses nothing but Gradle's own API and project-relative paths.
//
// Warm runs: hadronWatch starts a test server that stays up (:neoforge:runHadronWatch); each hadronRerun stages a
// fresh build there and waits for its report, without a server boot. hadronWatchStop stops it. All three tasks take
// --tests, as Gradle's test task does.
//
// The applying project compiles its tests against test-api and Minecraft:
//     neoForge.addModdingDependenciesTo(sourceSets.test.get())
//     testCompileOnly(project(":test-api"))

/** A task that runs some of a plugin's tests: all of them, unless --tests names some. */
abstract class HadronTestTask : DefaultTask() {
    @get:Input
    @get:Option(
        option = "tests",
        description = "Runs only the tests matching this: a class (HelloTests), a method (HelloTests.greets), or "
                + "either with * (HelloTests.greet*). Repeat it for more."
    )
    abstract val tests: ListProperty<String>

    companion object {
        /** Prints a report and fails unless this suite's tests all passed; the same check for cold and warm runs. */
        fun check(task: Task, report: File, jar: String, log: File, extra: String = "") {
            if (!report.isFile) {
                throw GradleException("The test server stopped before writing its report, so no test ran to the end. "
                        + "Its log says why: $log$extra")
            }
            val text = report.readText()
            task.logger.lifecycle(text)
            val json = File(report.parentFile, report.name.removeSuffix(".txt") + ".json")
            val summary = text.lines().firstOrNull { it.startsWith("SUMMARY $jar ") }
                ?: throw GradleException("The report has no results for $jar: the server stopped before reaching it, "
                        + "or it wasn't staged. Report: $report; log: $log$extra")
            if (!Regex(""" passed, 0 failed$""").containsMatchIn(summary) || !text.trimEnd().endsWith("RESULT: PASSED")) {
                throw GradleException("Hadron tests failed (${summary.removePrefix("SUMMARY ")}). The failures are "
                        + "above and in $report; as JSON: $json$extra")
            }
            task.logger.lifecycle("Report: $report\nJSON: $json")
        }
    }
}

/** Stages a build into a watching test server's inbox; with [waitForReport], waits for its run and checks it. */
abstract class HadronWatchTask : HadronTestTask() {
    @get:InputFiles
    abstract val jar: ConfigurableFileCollection

    @get:Internal
    abstract val watchDir: DirectoryProperty

    @get:Input
    abstract val suiteJar: Property<String>

    @get:Input
    abstract val waitForReport: Property<Boolean>

    /** Hadron as the server loads it (its compiled classes and resources, or its jars). */
    @get:Internal
    abstract val hadronLoaded: ConfigurableFileCollection

    /** The build files that shape the server, path to hash, read as Gradle configured this build. */
    @get:Input
    abstract val hadronConfig: MapProperty<String, String>

    /** The build's root folder, so fingerprint paths read like the repo's. */
    @get:Internal
    abstract val buildRoot: Property<String>

    @get:Input
    abstract val watchCommand: Property<String>

    init {
        // A rerun is asked for, never up to date.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun stage() {
        val dir = watchDir.get().asFile
        if (!waitForReport.get()) {
            // What the server about to start loads: its run task moves this into place as it starts.
            writeFingerprint(dir.resolve(PENDING_FINGERPRINT), fingerprint())
            put(dir)
            return
        }
        serverStarted(dir) ?: throw GradleException("No Hadron test server is watching, so there's "
                + "nothing to rerun on. Start one with ./gradlew ${watchCommand.get()} (it keeps running: give it its "
                + "own terminal or the background), then run this again.")
        val loaded = readFingerprint(dir.resolve(FINGERPRINT)) ?: throw GradleException("The watching test server "
                + "has no record of the Hadron it loaded (${dir.resolve(FINGERPRINT)}), so this can't tell whether it is "
                + "current. Stop it with hadronWatchStop and start ./gradlew ${watchCommand.get()} again.")
        val now = fingerprint()
        val changed = (loaded.keys + now.keys).filter { loaded[it] != now[it] }.sorted()
        if (changed.isNotEmpty()) {
            throw GradleException("Hadron changed since the watching test server started (${changed.take(5)
                .joinToString()}${if (changed.size > 5) " and ${changed.size - 5} more" else ""}), so it would test "
                    + "this plugin against the old Hadron. Stop it with hadronWatchStop and start "
                    + "./gradlew ${watchCommand.get()} again.")
        }
        val token = put(dir)
        val answer = dir.resolve("done/$token.properties")
        val log = dir.resolve("run/logs/latest.log")
        val deadline = System.currentTimeMillis() + 15 * 60_000L
        while (!answer.isFile) {
            if (serverStarted(dir) == null && !answer.isFile) {
                throw GradleException("The watching test server ended before it ran this build. Its log says why: "
                        + "$log. Start ./gradlew ${watchCommand.get()} again.")
            }
            if (System.currentTimeMillis() > deadline) {
                throw GradleException("The watching test server didn't run this build within 15 minutes; it may be "
                        + "stuck. Its log: $log")
            }
            Thread.sleep(50)
        }
        val props = java.util.Properties()
        answer.reader().use { props.load(it) }
        if (props.getProperty("status") == "superseded") {
            throw GradleException("A newer build of ${suiteJar.get()} was staged before this one ran, so the server "
                    + "ran that one instead; its hadronRerun prints the result.")
        }
        val alive = serverStarted(dir) != null
        check(this, File(props.getProperty("report")), suiteJar.get(), log,
            if (alive) "" else ". The test server ended after this run (a hung test ends it); start "
                    + "./gradlew ${watchCommand.get()} again.")
    }

    /** Puts the jar and its filter in the inbox as one folder, renamed into place so the server sees all or none. */
    private fun put(dir: File): String {
        val inbox = dir.resolve("inbox")
        inbox.mkdirs()
        val token = "%013d-%08x".format(System.currentTimeMillis(), java.util.Random().nextInt())
        val part = inbox.resolve(".$token")
        part.mkdirs()
        jar.singleFile.copyTo(part.resolve(suiteJar.get()), overwrite = true)
        val props = java.util.Properties()
        props.setProperty("jar", suiteJar.get())
        props.setProperty("tests", tests.get().joinToString(","))
        part.resolve("suite.properties").writer().use { props.store(it, null) }
        java.nio.file.Files.move(part.toPath(), inbox.resolve(token).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return token
    }

    /** Every loaded file and build file, by path, to its content's hash: equal maps mean the same Hadron. */
    private fun fingerprint(): Map<String, String> {
        val out = sortedMapOf<String, String>()
        out.putAll(hadronConfig.get())
        val base = File(buildRoot.get())
        for (root in hadronLoaded.files) {
            val name = root.relativeTo(base).invariantSeparatorsPath
            if (root.isFile) out[name] = sha(root)
            else if (root.isDirectory) root.walkTopDown().filter { it.isFile }.forEach {
                out[name + "/" + it.relativeTo(root).invariantSeparatorsPath] = sha(it)
            }
            else out[name] = "missing"
        }
        return out
    }

    companion object {
        const val FINGERPRINT = "hadron.fingerprint"
        const val PENDING_FINGERPRINT = "hadron.fingerprint.pending"

        fun sha(file: File): String = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

        fun writeFingerprint(file: File, entries: Map<String, String>) {
            file.parentFile.mkdirs()
            file.writeText(entries.entries.joinToString("\n", postfix = "\n") { "${it.value} ${it.key}" })
        }

        fun readFingerprint(file: File): Map<String, String>? = if (!file.isFile) null else
            file.readLines().filter { it.isNotBlank() }.associate { it.substringAfter(' ') to it.substringBefore(' ') }

        /** When the watching server started, or null if none is running. */
        fun serverStarted(dir: File): Long? {
            val file = dir.resolve("watch.properties")
            if (!file.isFile) return null
            val props = java.util.Properties()
            try {
                file.reader().use { props.load(it) }
            } catch (e: java.io.IOException) {
                return null
            }
            val pid = props.getProperty("pid")?.toLongOrNull() ?: return null
            val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            return if (alive) props.getProperty("started")?.toLongOrNull() else null
        }
    }
}

/** Asks a watching test server to stop and waits until it has. */
abstract class HadronWatchStopTask : DefaultTask() {
    @get:Internal
    abstract val watchDir: DirectoryProperty

    @TaskAction
    fun stop() {
        val dir = watchDir.get().asFile
        if (HadronWatchTask.serverStarted(dir) == null) {
            logger.lifecycle("No Hadron test server is watching.")
            return
        }
        dir.resolve("stop").writeText("")
        val deadline = System.currentTimeMillis() + 60_000L
        while (HadronWatchTask.serverStarted(dir) != null) {
            if (System.currentTimeMillis() > deadline) {
                throw GradleException("The watching test server didn't stop within a minute. Its pid is in "
                        + dir.resolve("watch.properties") + "; end it from there.")
            }
            Thread.sleep(200)
        }
        logger.lifecycle("The Hadron test server stopped.")
    }
}

val sourceSets = the<SourceSetContainer>()
val main = sourceSets.getByName("main")
val test = sourceSets.getByName("test")
// One staged jar per project, named for its path, so the report can tell suites apart.
val suiteJar = project.path.removePrefix(":").replace(':', '-') + ".jar"
val suiteName = suiteJar
val testRoot = rootProject.layout.projectDirectory.dir("neoforge/build/hadron-test")
val watchRoot = rootProject.layout.projectDirectory.dir("neoforge/build/hadron-watch")

// The test classes the server should look at, so it never loads the plugin's own classes just to search them.
val hadronTestIndex = tasks.register("hadronTestIndex") {
    val classes = test.output.classesDirs
    val out = layout.buildDirectory.dir("hadron-test-index")
    inputs.files(classes)
    outputs.dir(out)
    doLast {
        val names = classes.files.filter { it.isDirectory }.flatMap { dir ->
            // Only classes holding an @HadronTest: the server loads each one, and a fast JUnit test class can name
            // types (TestInfo, extensions) the server doesn't have. A class file names its annotations' types in its
            // constant pool, so finding the descriptor's bytes is enough to tell.
            val marker = "Ldev/iredsc/hadron/test/HadronTest;".toByteArray(Charsets.UTF_8)
            fun holdsHadronTest(bytes: ByteArray): Boolean {
                outer@ for (i in 0..bytes.size - marker.size) {
                    for (j in marker.indices) if (bytes[i + j] != marker[j]) continue@outer
                    return true
                }
                return false
            }
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && holdsHadronTest(it.readBytes()) }
                .map { it.relativeTo(dir).path.removeSuffix(".class").replace(File.separatorChar, '.') }.toList()
        }.sorted()
        val index = out.get().file("META-INF/hadron-tests.txt").asFile
        index.parentFile.mkdirs()
        index.writeText(names.joinToString("\n", postfix = "\n"))
    }
}

val hadronTestJar = tasks.register<Jar>("hadronTestJar") {
    description = "The plugin jar plus its tests, as the test server loads it."
    archiveClassifier.set("hadron-test")
    from(main.output)
    from(test.output)
    from(hadronTestIndex)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val hadronTest = tasks.register<HadronTestTask>("hadronTest") {
    group = "verification"
    description = "Runs this plugin's @HadronTests in a headless server and fails if any fails."
}

val hadronTestStage = tasks.register<Copy>("hadronTestStage") {
    from(hadronTestJar)
    val suites = testRoot.dir("suites").asFile
    into(suites)
    val name = suiteJar
    rename { name }
    // The test run moves the staged jar away, so a stage is never up to date.
    outputs.upToDateWhen { false }
    // Beside the jar, the --tests patterns, if any; the run moves it with the jar.
    val tests = hadronTest.flatMap { it.tests }
    doLast {
        val filter = suites.resolve("$name.tests")
        val patterns = tests.get()
        if (patterns.isEmpty()) filter.delete() else filter.writeText(patterns.joinToString("\n", postfix = "\n"))
    }
}

hadronTest.configure {
    dependsOn(hadronTestStage, ":neoforge:runHadronTest")
    val report = testRoot.file("report.txt").asFile
    val log = testRoot.file("run/logs/latest.log").asFile
    val jar = suiteJar
    // runHadronTest copies a failed run's report and log there (HadronTestServer.keep); a rerun keeps its own.
    val kept = testRoot.dir("failures").asFile
    doLast { HadronTestTask.check(this, report, jar, log, ". A copy of each failed run is kept in $kept") }
}

val startCommand = project.path + ":hadronWatch"
// What a watching server loads of Hadron, and the tasks that build it, so a rerun can tell the server is current.
val hadronModules = listOf("api", "common", "test-api", "neoforge")
val loadedHadron = rootProject.files(hadronModules.flatMap { listOf("$it/build/classes/java/main", "$it/build/resources/main") })
val hadronBuiltBy = hadronModules.map { ":$it:classes" }
// The test harness, a mod of its own beside Hadron's (neoforge's harness source set): the server loads it too.
val loadedHarness = rootProject.files("neoforge/build/classes/java/harness", "neoforge/build/resources/harness")
val harnessBuiltBy = listOf(":neoforge:harnessClasses")
// The build files that shape the server, hashed as Gradle read them to configure this build.
val hadronConfigFiles = listOf("gradle.properties", "settings.gradle.kts", "build.gradle.kts",
    "gradle/hadron-test-server.gradle.kts") + hadronModules.map { "$it/build.gradle.kts" }
val hadronConfigHashes = hadronConfigFiles.associateWith { path ->
    rootProject.file(path).let { if (it.isFile) HadronWatchTask.sha(it) else "missing" }
}
val firstRun = watchRoot.file("last-run.properties").asFile

val hadronWatch = tasks.register<HadronTestTask>("hadronWatch") {
    group = "verification"
    description = "Starts a test server that stays up and runs this plugin's @HadronTests; hadronRerun reruns them."
}

val hadronWatchStage = tasks.register<HadronWatchTask>("hadronWatchStage") {
    description = "Stages this plugin's tests for the test server hadronWatch starts."
    jar.from(hadronTestJar)
    watchDir.set(watchRoot)
    suiteJar.set(suiteName)
    waitForReport.set(false)
    tests.set(hadronWatch.flatMap { it.tests })
    watchCommand.set(startCommand)
}

hadronWatchStage.configure {
    dependsOn(hadronBuiltBy, harnessBuiltBy)
    hadronLoaded.from(loadedHadron, loadedHarness)
    hadronConfig.set(hadronConfigHashes)
    buildRoot.set(rootProject.projectDir.path)
}

hadronWatch.configure {
    dependsOn(hadronWatchStage, ":neoforge:runHadronWatch")
    // The server ran and stopped as asked (its run task fails on any other exit); it must also have run a build.
    val last = firstRun
    val log = watchRoot.file("run/logs/latest.log").asFile
    doLast {
        if (!last.isFile) {
            throw GradleException("The watching test server stopped before it finished running any tests, so it "
                    + "never proved it works. Its log says why: $log")
        }
        logger.lifecycle("The Hadron test server stopped. Its last run: $last")
    }
}

tasks.register<HadronWatchTask>("hadronRerun") {
    group = "verification"
    description = "Reruns this plugin's @HadronTests on the server hadronWatch started, and fails if any fails."
    jar.from(hadronTestJar)
    watchDir.set(watchRoot)
    suiteJar.set(suiteName)
    waitForReport.set(true)
    dependsOn(hadronBuiltBy, harnessBuiltBy)
    hadronLoaded.from(loadedHadron, loadedHarness)
    hadronConfig.set(hadronConfigHashes)
    buildRoot.set(rootProject.projectDir.path)
    watchCommand.set(startCommand)
}

tasks.register<HadronWatchStopTask>("hadronWatchStop") {
    description = "Stops the test server hadronWatch started."
    watchDir.set(watchRoot)
}

// A plugin whose tests are all @HadronTests has test sources but no JUnit tests, and Gradle 9 fails its `test` task
// for finding none. hadronTest is what runs those, and it refuses an empty run itself (a filter matching nothing is a
// BROKEN suite), so the JUnit task may find nothing without hiding a mistake.
tasks.withType<Test>().configureEach {
    if (name == "test") failOnNoDiscoveredTests.set(false)
}
