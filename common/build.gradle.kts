// The plugin host, shared by every loader. Vanilla Minecraft only; what a loader must supply comes through Platform.
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
    id("info.solidsoft.pitest") version "1.19.0"
}

version = providers.gradleProperty("hadron_version").get()
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
repositories { mavenCentral() }

neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
}

dependencies {
    api(project(":api"))
}

// Fast tests: JUnit on the JVM, no server. ./gradlew :common:test. A class that needs Minecraft's registries carries
// @WithMinecraft from test-api; the rest don't boot Minecraft at all.
neoForge.addModdingDependenciesTo(sourceSets.test.get())

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":test-api"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Mutation testing: ./gradlew :common:pitest breaks common's code on purpose, runs the fast tests against each break, and
// fails below the score the tests have reached, so it can only go up. Slow (about two minutes), so not part of check;
// run it before a PR that touches api or common. See "Mutation testing" in docs/testing.md.
pitest {
    pitestVersion = "1.30.2"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("dev.iredsc.hadron.common.*")
    // Text for people, never a decision; docs/testing.md lists every exclusion and why.
    excludedMethods = setOf("toString", "logMissingDefault")
    threads = 2
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    // Each minion JVM boots Minecraft's registries once (@WithMinecraft); give it room and time for that.
    jvmArgs = listOf("-Xmx1536m")
    timeoutConstInMillis = 10000
    // The gate: what the tests reached, 350 of 1228 mutants killed and 350 of 360 reached. 350 is 28.5%, on PIT's
    // rounding edge, and one kill depends on timing, so the score gate is 28 rather than 29.
    // Raise it when the score rises; never lower it.
    mutationThreshold = 28
    testStrengthThreshold = 97
}

// After every run, pass or fail: the score and each surviving mutant, so nobody has to open the report to start.
val pitestSurvivors = tasks.register("pitestSurvivors") {
    val xml = layout.buildDirectory.file("reports/pitest/mutations.xml")
    val html = layout.buildDirectory.file("reports/pitest/index.html")
    doLast {
        val file = xml.get().asFile
        if (!file.isFile) return@doLast
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val all = doc.getElementsByTagName("mutation")
        val counts = mutableMapOf<String, Int>()
        val survivors = mutableListOf<String>()
        for (i in 0 until all.length) {
            val m = all.item(i) as org.w3c.dom.Element
            val status = m.getAttribute("status")
            counts.merge(status, 1, Int::plus)
            fun text(tag: String) = m.getElementsByTagName(tag).item(0).textContent
            if (status == "SURVIVED") {
                survivors += "  ${text("sourceFile")}:${text("lineNumber")} ${text("mutatedMethod")}: ${text("description")}"
            }
        }
        val total = all.length
        val killed = (counts["KILLED"] ?: 0) + (counts["TIMED_OUT"] ?: 0) + (counts["MEMORY_ERROR"] ?: 0)
        val covered = total - (counts["NO_COVERAGE"] ?: 0)
        fun pct(n: Int, d: Int) = if (d == 0) 100 else Math.round(n * 100f / d) // as PIT rounds
        println("Mutation score ${pct(killed, total)}% ($killed of $total killed), test strength " +
                "${pct(killed, covered)}% of the $covered the fast tests reach; ${counts["NO_COVERAGE"] ?: 0} unreached")
        println(if (survivors.isEmpty()) "No surviving mutants." else "Surviving mutants (${survivors.size}): " +
                "each is a decision no test pins, or an equivalent change")
        survivors.sorted().forEach(::println)
        println("Report: ${html.get().asFile}")
    }
}
tasks.named("pitest") { finalizedBy(pitestSurvivors) }

// Compile-time checks (gradle/hadron-checks.gradle.kts): Error Prone's errors, NullAway, and Hadron's own checks.
dependencies {
    compileOnly(project(":build-checks"))
    testCompileOnly(project(":build-checks"))
}
extra["hadronChecks.nullAway"] = listOf("main")
extra["hadronChecks.exemptions"] = true
extra["hadronChecks.staticState"] = listOf("main")
// common is the host whose promises tests rely on, so it never times a decision by the wall clock; its tests are
// deterministic too.
extra["hadronChecks.determinism"] = listOf("main", "test")
apply(from = rootProject.file("gradle/hadron-checks.gradle.kts"))
