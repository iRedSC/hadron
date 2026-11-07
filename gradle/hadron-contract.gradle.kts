// contractBundle: writes build/contract/, a folder holding only Hadron's contract, for a test agent that writes tests
// blind to the implementation (docs/design.md, "Built to be verified"; docs/testing.md, "Blind tests").
//
//     build/contract/README.md         what the blind agent may read and do, how to run and report
//     build/contract/contract/src/     stubs of api and test-api (gradle/contract/ContractStubs.java)
//     build/contract/contract/docs/    the spec docs, stripped by the rules in ContractDocs below
//     build/contract/tests/            a Gradle project running both tiers against the real Hadron jars in tests/libs/
//
// contractLeakCheck (gradle/contract/ContractLeakCheck.java, its own reading of the rules) checks the contract before
// anything is assembled, so a leak fails the task instead of landing in the folder. Applied from the root build.

apply(plugin = "jvm-toolchains")

// Everything the doc stripping does, in one object: the configuration cache refuses task actions that reach into
// the script itself.
object ContractDocs {
    /**
     * One doc's rules. [sections]: headings removed with everything under them, up to the next heading of the same or a
     * higher level. [replace]: exact text, each found exactly once, replaced (an empty replacement removes it).
     * [chokepoints]: the "Core chokepoint" table column becomes "Fired by" (core, bridge, or both, with no code names).
     */
    class Rules(val sections: List<String> = listOf(), val replace: List<Pair<String, String>> = listOf(),
                val chokepoints: Boolean = false)

    val DOCS: Map<String, Rules> = linkedMapOf(
        "events.md" to Rules(
            chokepoints = true,
            replace = listOf(
                // How a bridge must time its event, without naming the vanilla method core hooks.
                "Bridges fire it at the start of the mod's action, not at the last vanilla call inside it: Iron Chests " +
                    "and Carry On remove a block entity before calling `setBlock`, so cancelling at `setBlock` is " +
                    "already too late." to
                    "Bridges fire it at the start of the mod's action, not at the last vanilla call inside it: Iron " +
                    "Chests and Carry On remove a block entity before placing the new block, so cancelling at the " +
                    "placement is already too late.",
                // Why events aren't shipped yet, without where core would hook them.
                "- `EntityRemoveEvent`, `EntityCaptureEvent`: cancelling at `Entity.remove` can duplicate (a cancelled " +
                    "arrow pickup); each cause needs its own hook." to
                    "- `EntityRemoveEvent`, `EntityCaptureEvent`: cancelling one could duplicate (a cancelled arrow " +
                    "pickup); each cause needs its own hook.",
            ),
        ),
        "platform.md" to Rules(),
        "bridge-hooks.md" to Rules(
            replace = listOf(
                // A note to Hadron's maintainers, not contract.
                "Keep it current: whenever an event's Javadoc says bridges fire it, or core's coverage of an event " +
                    "changes, update the tables here in the same change.\n\n" to "",
            ),
        ),
        "testing.md" to Rules(
            // The harness's internals, Hadron's own suite, and how bundles are made: not what a test author needs.
            sections = listOf("## How a run works", "## What Hadron's own tests cover", "## Blind tests",
                    "## Mutation testing"),
            replace = listOf(
                "`./gradlew test` (`:api:test`, `:common:test`, a plugin's own `test`)" to "`./gradlew test`",
                "`examples/hello`'s `HelloFastTest` is the example to copy: it checks the plugin's packed " +
                    "`plugin.yml` against Hadron's own rules, so a bad one fails at `./gradlew test` instead of at " +
                    "server start." to "`ExampleFastTest` and `ExampleTests` in this bundle's `tests/` are one of " +
                    "each to copy.",
                "./gradlew :examples:hello:hadronWatch          #" to "./gradlew hadronWatch          #",
                "./gradlew :examples:hello:hadronRerun          #" to "./gradlew hadronRerun          #",
                "./gradlew -t :examples:hello:hadronRerun       #" to "./gradlew -t hadronRerun       #",
                "./gradlew :examples:hello:hadronWatchStop      #" to "./gradlew hadronWatchStop      #",
                "in `neoforge/build/hadron-watch/run`" to "in `server/build/hadron-watch/run`",
                "if Hadron's own sources changed since the server started, `hadronRerun` fails and says to restart " +
                    "it, since the server still runs the old Hadron." to
                    "if Hadron's jars in `libs/` changed since the server started, `hadronRerun` fails and says to " +
                    "restart it, since the server still runs the old Hadron.",
                "- The run is wired into `examples/hello` and `selftest` with `apply(from = ...)`; other projects get " +
                    "it from the Gradle plugin.\n" to "",
                "`coverageCheck` (part of `check`; `./gradlew :selftest:coverageCheck`) reads class files only, " +
                    "`api`'s and the compiled tests' (`selftest`, and `api`'s and `common`'s JUnit tests when there " +
                    "are any), so" to "`coverageCheck` (part of `check`) reads class files only, `api`'s and the " +
                    "compiled tests', so",
            ),
        ),
    )

    val CODE = Regex("`([^`]+)`")
    val LINK = Regex("""\[([^\]]+)]\(([^)#\s]+\.md)(#[^)]*)?\)""")

    /** Writes the stripped docs into [out], and into [forbidden] what must no longer appear in them. */
    fun strip(docs: File, out: File, forbidden: File, apiTypes: Set<String>) {
        out.deleteRecursively()
        out.mkdirs()
        val rules = StringBuilder()
        for ((name, r) in DOCS) {
            var text = docs.resolve(name).readText()
            for (heading in r.sections) {
                text = removeSection(name, text, heading)
                rules.append("$name\t$heading\n")
            }
            for ((old, new) in r.replace) {
                val count = text.split(old).size - 1
                if (count != 1) {
                    throw GradleException("contractBundle: docs/$name has ${if (count == 0) "no" else count} copies of a "
                            + "passage its stripping rule replaces, so the rule no longer fits the doc. Review the "
                            + "passage and update ContractDocs in gradle/hadron-contract.gradle.kts: \"$old\"")
                }
                text = text.replace(old, new)
                // The removed passage must be gone from the result.
                old.lines().filter { it.length >= 20 && it !in new }.forEach { rules.append("$name\t$it\n") }
            }
            if (r.chokepoints) {
                val (stripped, tokens) = chokepoints(name, text)
                text = stripped
                rules.append("$name\tCore chokepoint\n")
                tokens.filter { t -> t.split('.', '(').first().trim('`') !in apiTypes }.sorted().distinct()
                        .forEach { rules.append("$name\t$it\n") }
            }
            // Links to docs outside the bundle become their text.
            text = LINK.replace(text) { m -> if (m.groupValues[2] in DOCS) m.value else m.groupValues[1] }
            out.resolve(name).writeText(text)
        }
        forbidden.parentFile.mkdirs()
        forbidden.writeText(rules.toString())
    }

    fun removeSection(doc: String, text: String, heading: String): String {
        val lines = text.lines()
        val start = lines.indexOf(heading)
        if (start < 0) {
            throw GradleException("contractBundle: docs/$doc has no section \"$heading\", which its stripping rule "
                    + "removes. Update ContractDocs in gradle/hadron-contract.gradle.kts to match the doc.")
        }
        val level = heading.takeWhile { it == '#' }.length
        var end = start + 1
        while (end < lines.size) {
            val l = lines[end]
            val hashes = l.takeWhile { it == '#' }.length
            if (hashes in 1..level && l.getOrNull(hashes) == ' ') break
            end++
        }
        return (lines.subList(0, start) + lines.subList(end, lines.size)).joinToString("\n")
    }

    /** Replaces every table's "Core chokepoint" column with "Fired by"; returns the text and the code names removed. */
    fun chokepoints(doc: String, text: String): Pair<String, List<String>> {
        val tokens = mutableListOf<String>()
        val out = mutableListOf<String>()
        var column = -1
        var tables = 0
        for (line in text.lines()) {
            if (!line.startsWith("|")) {
                column = -1
                out += line
                continue
            }
            val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").toMutableList()
            if (column < 0) {
                column = cells.indexOfFirst { it.trim() == "Core chokepoint" }
                if (column < 0) {
                    column = Int.MAX_VALUE // a table without the column: leave it
                } else {
                    tables++
                    cells[column] = " Fired by "
                    out += "|" + cells.joinToString("|") + "|"
                    continue
                }
            }
            if (column == Int.MAX_VALUE) {
                out += line
                continue
            }
            if (column >= cells.size) {
                throw GradleException("contractBundle: a row of a table in docs/$doc has no \"Core chokepoint\" cell, "
                        + "so the column can't be stripped safely: $line")
            }
            val cell = cells[column].trim()
            if (cell.matches(Regex("-+"))) {
                out += line
                continue
            }
            CODE.findAll(cell).forEach { tokens += it.value }
            val firedBy = when {
                cell.isEmpty() -> ""
                '`' !in cell -> cell // prose about who fires it ("bridge (Create, ...)", "core for currencies; ...")
                Regex("""\bbridges?\b""").containsMatchIn(cell) -> "core; bridges too"
                else -> "core"
            }
            cells[column] = if (firedBy.isEmpty()) " " else " $firedBy "
            out += "|" + cells.joinToString("|") + "|"
        }
        if (tables == 0) {
            throw GradleException("contractBundle: docs/$doc has no \"Core chokepoint\" column any more. Update "
                    + "ContractDocs in gradle/hadron-contract.gradle.kts to match the doc.")
        }
        return out.joinToString("\n") to tokens
    }

    /**
     * Hadron's test runner, rewired for the bundle: its server is the bundle's :server project, not the repo's
     * :neoforge, the suite has a fixed name, and "Hadron changed" means its jars in libs/. Every rewrite must still
     * find its text, so a change to the runner can't slip past unadapted.
     */
    fun testRunner(source: String): String {
        var text = source
        fun once(old: String, new: String) {
            val count = text.split(old).size - 1
            if (count != 1) throw GradleException("contractBundle: gradle/hadron-test.gradle.kts has $count copies of "
                    + "\"$old\"; update ContractDocs.testRunner in gradle/hadron-contract.gradle.kts to match it.")
            text = text.replace(old, new)
        }
        once("val suiteJar = project.path.removePrefix(\":\").replace(':', '-') + \".jar\"",
                "val suiteJar = \"contract-tests.jar\"")
        once("rootProject.files(listOf(\"api\", \"common\", \"test-api\", \"neoforge\").map { \"\$it/src/main\" })",
                "rootProject.files(\"libs\")")
        once("\"neoforge/build/hadron-test\"", "\"server/build/hadron-test\"")
        once("\"neoforge/build/hadron-watch\"", "\"server/build/hadron-watch\"")
        once("\":neoforge:runHadronTest\")", "\":server:runHadronTest\")")
        once("\":neoforge:runHadronWatch\")", "\":server:runHadronWatch\")")
        return text.replace(":neoforge:", ":server:")
    }
}

val parts = layout.buildDirectory.dir("contract-parts")
val java21 = extensions.getByType<JavaToolchainService>()
        .launcherFor { languageVersion = JavaLanguageVersion.of(21) }
val javacInternals = listOf(
    "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
)

val contractStubs = tasks.register<Exec>("contractStubs") {
    description = "Writes stubs of api's and test-api's public types: signatures and Javadoc, no bodies."
    val script = file("gradle/contract/ContractStubs.java")
    val api = file("api/src/main/java")
    val testApi = file("test-api/src/main/java")
    val out = parts.get().dir("contract/src").asFile
    inputs.file(script)
    inputs.dir(api)
    inputs.dir(testApi)
    outputs.dir(out)
    executable(java21.get().executablePath.asFile)
    args(javacInternals + listOf(script.path, out.path, "api=$api", "test-api=$testApi"))
    doFirst { out.deleteRecursively() }
}

val implNames = parts.map { it.file("rules/impl-names.txt") }
val forbidden = parts.map { it.file("rules/forbidden.txt") }

val contractDocs = tasks.register("contractDocs") {
    description = "Writes the spec docs with implementation detail stripped."
    val docs = file("docs")
    val api = file("api/src/main/java")
    val impl = files("common/src/main/java", "neoforge/src/main/java")
    val rules = file("gradle/hadron-contract.gradle.kts")
    val out = parts.get().dir("contract/docs").asFile
    val forbiddenFile = forbidden.get().asFile
    val implFile = implNames.get().asFile
    inputs.dir(docs)
    inputs.dir(api)
    inputs.files(impl)
    inputs.file(rules)
    outputs.dir(out)
    outputs.files(forbiddenFile, implFile)
    doLast {
        val apiTypes = api.walkTopDown().filter { it.name.endsWith(".java") }.map { it.name.removeSuffix(".java") }.toSet()
        ContractDocs.strip(docs, out, forbiddenFile, apiTypes)
        // Every top-level class name in the implementation: none may appear anywhere in the contract.
        val names = impl.files.flatMap { dir -> dir.walkTopDown().filter { it.name.endsWith(".java") }.toList() }
                .map { it.name.removeSuffix(".java") }.toSortedSet()
        implFile.parentFile.mkdirs()
        implFile.writeText(names.joinToString("\n", postfix = "\n"))
    }
}

val contractLeakCheck = tasks.register<Exec>("contractLeakCheck") {
    description = "Fails if the contract holds a body, a private member, an implementation class, or stripped docs text."
    dependsOn(contractStubs, contractDocs)
    val script = file("gradle/contract/ContractLeakCheck.java")
    val contract = parts.get().dir("contract").asFile
    val extra = files("gradle/contract/README.md", "gradle/contract/tests/src")
    val marker = parts.get().file("rules/leak-check.ok").asFile
    inputs.file(script)
    inputs.dir(contract)
    inputs.files(extra)
    inputs.files(implNames, forbidden)
    outputs.file(marker)
    executable(java21.get().executablePath.asFile)
    args(javacInternals + listOf(script.path, contract.path, implNames.get().asFile.path, forbidden.get().asFile.path)
            + extra.files.map { it.path })
    doLast { marker.writeText("clean\n") }
}

// Hadron's jars as the bundle ships them: repacked without timestamps, so the same sources make the same bytes.
fun repack(name: String, from: String, fileName: String) = tasks.register<Zip>(name) {
    dependsOn(from)
    from(zipTree(project(from.substringBeforeLast(':')).tasks.named<Jar>(from.substringAfterLast(':'))
            .flatMap { it.archiveFile }))
    archiveFileName.set(fileName)
    destinationDirectory.set(parts.map { it.dir("libs") })
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
val apiJar = repack("contractApiJar", ":api:jar", "hadron-api.jar")
val testApiJar = repack("contractTestApiJar", ":test-api:jar", "hadron-test-api.jar")
val modJar = repack("contractModJar", ":neoforge:jar", "hadron.jar")

val testRunner = tasks.register("contractTestRunner") {
    description = "Rewires Hadron's test runner script for the bundle's test project."
    val source = file("gradle/hadron-test.gradle.kts")
    val out = parts.get().file("gradle/hadron-test.gradle.kts").asFile
    inputs.file(source)
    inputs.file("gradle/hadron-contract.gradle.kts")
    outputs.file(out)
    doLast {
        out.parentFile.mkdirs()
        out.writeText(ContractDocs.testRunner(source.readText()))
    }
}

val readmeTokens = mapOf(
    "NEO_VERSION" to providers.gradleProperty("neo_version").get(),
    "MINECRAFT_VERSION" to providers.gradleProperty("minecraft_version").get(),
)

tasks.register<Sync>("contractBundle") {
    group = "verification"
    description = "Writes build/contract/: Hadron's contract and a test project on its real jars, for blind tests."
    dependsOn(contractLeakCheck)
    into(layout.buildDirectory.dir("contract"))
    from("gradle/contract/README.md") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to readmeTokens)
    }
    from(parts.map { it.dir("contract") }) { into("contract") }
    into("tests") {
        from("gradle/contract/tests") { exclude("**/build/**", "**/.gradle/**") }
        from("gradle.properties")
        from("gradlew", "gradlew.bat")
        from("gradle/wrapper") { into("gradle/wrapper") }
        from("gradle/hadron-coverage.gradle.kts") { into("gradle") }
        from("gradle/hadron-test-server.gradle.kts") { into("gradle") }
        from(testRunner) { into("gradle") }
        from(files(apiJar, testApiJar, modJar)) { into("libs") }
    }
    inputs.properties(readmeTokens)
    doLast {
        logger.lifecycle("Contract bundle: ${destinationDir}\n  Copy it anywhere; the blind agent starts at its README.md.")
    }
}
