// hadronTest for a plugin project: builds the plugin with its src/test/java classes into one jar, stages it for
// :neoforge:runHadronTest (a headless server that runs every @HadronTest and writes a report), then fails the build
// unless every one of this plugin's tests passed. Applied with apply(from = ...) for now; the Hadron Gradle plugin
// will own it, which is why it uses nothing but Gradle's own API and project-relative paths.
//
// The applying project compiles its tests against test-api and Minecraft:
//     neoForge.addModdingDependenciesTo(sourceSets.test.get())
//     testCompileOnly(project(":test-api"))

val sourceSets = the<SourceSetContainer>()
val main = sourceSets.getByName("main")
val test = sourceSets.getByName("test")
// One staged jar per project, named for its path, so the report can tell suites apart.
val suiteJar = project.path.removePrefix(":").replace(':', '-') + ".jar"
val testRoot = rootProject.layout.projectDirectory.dir("neoforge/build/hadron-test")

// The test classes the server should look at, so it never loads the plugin's own classes just to search them.
val hadronTestIndex = tasks.register("hadronTestIndex") {
    val classes = test.output.classesDirs
    val out = layout.buildDirectory.dir("hadron-test-index")
    inputs.files(classes)
    outputs.dir(out)
    doLast {
        val names = classes.files.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
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

val hadronTestStage = tasks.register<Copy>("hadronTestStage") {
    from(hadronTestJar)
    into(testRoot.dir("suites"))
    val name = suiteJar
    rename { name }
    // The test run moves the staged jar away, so a stage is never up to date.
    outputs.upToDateWhen { false }
}

tasks.register("hadronTest") {
    group = "verification"
    description = "Runs this plugin's @HadronTests in a headless server and fails if any fails."
    dependsOn(hadronTestStage, ":neoforge:runHadronTest")
    val report = testRoot.file("report.txt").asFile
    val log = testRoot.file("run/logs/latest.log").asFile
    val jar = suiteJar
    doLast {
        if (!report.isFile) {
            throw GradleException("The test server stopped before writing its report, so no test ran to the end. "
                    + "Its log says why: $log")
        }
        val text = report.readText()
        logger.lifecycle(text)
        val summary = text.lines().firstOrNull { it.startsWith("SUMMARY $jar ") }
            ?: throw GradleException("The report has no results for $jar: the server stopped before reaching it, "
                    + "or it wasn't staged. Report: $report; log: $log")
        if (!Regex(""" passed, 0 failed$""").containsMatchIn(summary)) {
            throw GradleException("Hadron tests failed (${summary.removePrefix("SUMMARY ")}). The failures are above "
                    + "and in $report")
        }
        logger.lifecycle("Report: $report")
    }
}
