// What plugins compile against. Vanilla Minecraft only: no NeoForge or Fabric class is on the classpath, so the API
// can't mention one, and a plugin built against it runs on every loader Hadron supports.
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
    id("info.solidsoft.pitest") version "1.19.0"
}

version = providers.gradleProperty("hadron_version").get()
java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    // Agents read the API from the sources jar, so every release ships one.
    withSourcesJar()
}
repositories { mavenCentral() }

neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
}

dependencies {
    // Plugins' config.yml. Minecraft doesn't ship a YAML parser, so it rides inside Hadron's jar.
    api("org.yaml:snakeyaml:2.2")
    // @Nullable on the API, so plugins' compilers and agents see which values can be missing. Compile-time only:
    // Java ignores an annotation whose class isn't there at runtime, so it doesn't ship.
    api("org.jspecify:jspecify:1.0.0")
}

// Fast tests: JUnit on the JVM, no server. ./gradlew :api:test. A class that needs Minecraft's registries carries
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

// Mutation testing: ./gradlew :api:pitest breaks api's code on purpose, runs the fast tests against each break, and
// fails below the score the tests have reached, so it can only go up. Slow (about two minutes), so not part of check;
// run it before a PR that touches api or common. See "Mutation testing" in docs/testing.md.
pitest {
    pitestVersion = "1.30.2"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("dev.iredsc.hadron.api.*")
    // Text for people, never a decision; docs/testing.md lists every exclusion and why.
    excludedMethods = setOf("toString")
    threads = 2
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    // Each minion JVM boots Minecraft's registries once (@WithMinecraft); give it room and time for that.
    jvmArgs = listOf("-Xmx1536m")
    timeoutConstInMillis = 10000
    // The gate: what the tests reached, 413 of 1436 mutants killed, 413 of 424 reached.
    // Raise it when the score rises; never lower it.
    mutationThreshold = 29
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
dependencies { compileOnly(project(":build-checks")) }
extra["hadronChecks.nullAway"] = listOf("main")
extra["hadronChecks.exemptions"] = true
extra["hadronChecks.staticState"] = listOf("main")
extra["hadronChecks.determinism"] = listOf<String>()
apply(from = rootProject.file("gradle/hadron-checks.gradle.kts"))
