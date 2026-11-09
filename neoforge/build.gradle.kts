import java.util.zip.ZipFile

// The NeoForge port: the mod that runs common's host and fires api's events from NeoForge's. One jar for server
// owners, holding api and common too.
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

version = providers.gradleProperty("hadron_version").get()
base.archivesName = "hadron"

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories { mavenCentral() }

// The in-game test harness (TestRunner, TestClient, TestWorld...) and test-api are a second mod, hadron_test, built
// from this source set. main can't see it, the release jar is main's, and only the test runs load it, so no test code
// can reach a server owner. releaseJarCheck (part of check) opens the built jar to make sure.
val harness: SourceSet = sourceSets.create("harness")

neoForge {
    version = providers.gradleProperty("neo_version").get()

    parchment {
        mappingsVersion = providers.gradleProperty("parchment_mappings_version").get()
        minecraftVersion = providers.gradleProperty("parchment_minecraft_version").get()
    }

    // Mods first: the runs below say which of them each loads.
    mods {
        register("hadron") {
            sourceSet(project(":api").sourceSets.main.get())
            sourceSet(project(":common").sourceSets.main.get())
            sourceSet(sourceSets.main.get())
        }
        register("hadron_test") {
            sourceSet(project(":test-api").sourceSets.main.get())
            sourceSet(harness)
        }
    }

    runs {
        register("server") {
            server()
            programArgument("--nogui")
            loadedMods.add(mods.named("hadron"))
        }
        // Test mode: a fresh server in build/hadron-test/run that runs the staged suites' @HadronTests and stops.
        // Plugins don't start it directly; their hadronTest task stages a suite and depends on this.
        register("hadronTest") {
            server()
            programArgument("--nogui")
            gameDirectory = layout.buildDirectory.dir("hadron-test/run")
            systemProperty("hadron.test.report", layout.buildDirectory.file("hadron-test/report.txt").get().asFile.absolutePath)
            disableIdeRun()
            loadedMods.addAll(mods.named("hadron").zip(mods.named("hadron_test")) { a, b -> listOf(a, b) })
            sourceSet.set(harness)
        }
        // Watch mode: the same server, but it stays up and reruns a suite each time a build stages it in
        // build/hadron-watch/inbox. Plugins start it with hadronWatch and rerun with hadronRerun.
        register("hadronWatch") {
            server()
            programArgument("--nogui")
            gameDirectory = layout.buildDirectory.dir("hadron-watch/run")
            systemProperty("hadron.test.watch", layout.buildDirectory.dir("hadron-watch").get().asFile.absolutePath)
            disableIdeRun()
            loadedMods.addAll(mods.named("hadron").zip(mods.named("hadron_test")) { a, b -> listOf(a, b) })
            sourceSet.set(harness)
        }
    }
}

neoForge.addModdingDependenciesTo(harness)

dependencies {
    implementation(project(":common"))
    "harnessImplementation"(sourceSets.main.get().output)
    "harnessImplementation"(project(":common"))
    "harnessImplementation"(project(":test-api"))
    // plugin.yml and plugins' config.yml. Minecraft doesn't ship a YAML parser, so it rides inside Hadron's jar.
    // One version everywhere: the same one api compiles against and dev runs use.
    jarJar("org.yaml:snakeyaml") { version { strictly("[2.2,3)"); prefer("2.2") } }
    // Dev runs don't unpack jarJar, so the game needs it on its classpath there too.
    "additionalRuntimeClasspath"("org.yaml:snakeyaml:2.2")
}

// Fast tests of the harness's decisions (which tests a filter picks): JUnit on the JVM, no server. They load only
// the classes they name, none of which touch Minecraft, so they need no Minecraft on their classpath.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(harness.output)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// api and common ship inside the mod's own jar, so server owners install one file.
tasks.jar {
    from(project(":api").sourceSets.main.get().output)
    from(project(":common").sourceSets.main.get().output)
}

// The harness mod as a jar, for test servers that install Hadron from jars rather than sources (contract bundles).
val harnessJar = tasks.register<Jar>("harnessJar") {
    description = "Builds the hadron_test mod: the in-game test harness and test-api. Never for a real server."
    archiveBaseName = "hadron-test"
    from(harness.output)
    from(project(":test-api").sourceSets.main.get().output)
}

val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val props = mapOf(
        "hadron_version" to providers.gradleProperty("hadron_version").get(),
        "minecraft_version_range" to providers.gradleProperty("minecraft_version_range").get(),
        "neo_version_range" to providers.gradleProperty("neo_version_range").get(),
    )
    inputs.properties(props)
    expand(props)
    from("src/main/templates")
    into(layout.buildDirectory.dir("generated/sources/modMetadata"))
}
sourceSets.main.get().resources.srcDir(generateModMetadata)
neoForge.ideSyncTask(generateModMetadata)

val generateHarnessMetadata = tasks.register<ProcessResources>("generateHarnessMetadata") {
    val props = mapOf("hadron_version" to providers.gradleProperty("hadron_version").get())
    inputs.properties(props)
    expand(props)
    from("src/harness/templates")
    into(layout.buildDirectory.dir("generated/sources/harnessMetadata"))
}
harness.resources.srcDir(generateHarnessMetadata)
neoForge.ideSyncTask(generateHarnessMetadata)

// The release jar holds no test code: no harness or test-api class, nothing of a test plugin's, and none of their
// resources. The harness's own source set makes that structural; this reads the built jar so a stray from() can't
// undo it unnoticed. Forbidden are every file the harness and test-api build, plus the test packages by name.
val releaseJarCheck = tasks.register("releaseJarCheck") {
    group = "verification"
    description = "Fails if Hadron's mod jar holds a test harness, test-api or test plugin file, naming each one."
    val jar = tasks.jar.flatMap { it.archiveFile }
    val testOutputs = files(harness.output, project(":test-api").sourceSets.main.get().output)
    inputs.file(jar)
    inputs.files(testOutputs)
    val prefixes = listOf("dev/iredsc/hadron/harness/", "dev/iredsc/hadron/test/", "dev/iredsc/hadron/selftest/",
            "dev/iredsc/hadron/bench/", "dev/iredsc/hadron/examples/", "META-INF/hadron-tests.txt")
    doLast {
        val forbidden = testOutputs.files.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }.toSet()
        val file = jar.get().asFile
        val offending = ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.filter { !it.endsWith("/") }.toList()
            // Both mods have a neoforge.mods.toml, so that one is told apart by what it declares.
            val toml = zip.getEntry("META-INF/neoforge.mods.toml")?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } }
            val harnessToml = if (toml != null && Regex("modId\\s*=\\s*\"hadron_test\"").containsMatchIn(toml))
                listOf("META-INF/neoforge.mods.toml (declares the hadron_test mod)") else emptyList()
            names.filter { name -> name != "META-INF/neoforge.mods.toml" }
                .filter { name -> name in forbidden || prefixes.any { name.startsWith(it) } }.sorted() + harnessToml
        }
        if (offending.isNotEmpty()) {
            throw GradleException("Hadron's release jar $file holds test code a server owner must never get:\n  "
                    + offending.joinToString("\n  ") + "\nTest code belongs in neoforge/src/harness (the hadron_test "
                    + "mod) or test-api, which tasks.jar must not include: remove the from(...) that brings it in.")
        }
    }
}
tasks.named("check") { dependsOn(releaseJarCheck) }

// A fresh clone's dev server starts without questions: the EULA accepted, a flat world, offline mode, and RCON so an
// agent can run console commands (scripts/rcon.py). Files already there are left alone.
val prepareDevServer = tasks.register("prepareDevServer") {
    val run = layout.projectDirectory.dir("run").asFile
    doLast {
        run.mkdirs()
        val eula = run.resolve("eula.txt")
        if (!eula.exists()) eula.writeText("eula=true\n")
        val props = run.resolve("server.properties")
        if (!props.exists()) {
            props.writeText(
                """
                online-mode=false
                server-port=25599
                level-type=minecraft\:flat
                spawn-protection=0
                enable-rcon=true
                rcon.port=25598
                rcon.password=hadron
                broadcast-rcon-to-ops=false
                """.trimIndent() + "\n"
            )
        }
    }
}
tasks.named("runServer") { dependsOn(prepareDevServer) }

// The test server's setup is shared with contract bundles, so the two can't drift: see the script's top.
extra["hadronTestServer.stage"] = { stage: String ->
    rootProject.subprojects.mapNotNull { it.tasks.findByName(stage) }
}
apply(from = rootProject.file("gradle/hadron-test-server.gradle.kts"))

// bridgeHooksCheck (part of check): docs/bridge-hooks.md's tables match the @FiredBy on each event, and @FiredBy
// matches what core fires. ./gradlew bridgeHooks rewrites the tables.
apply(from = rootProject.file("gradle/hadron-bridge-hooks.gradle.kts"))

// Compile-time checks (gradle/hadron-checks.gradle.kts): Error Prone's errors, NullAway, and Hadron's own checks.
dependencies {
    compileOnly(project(":build-checks"))
    "harnessCompileOnly"(project(":build-checks"))
}
// The harness is Hadron's own code that ships to every plugin's test server, so it gets main's checks.
extra["hadronChecks.nullAway"] = listOf("main", "harness")
extra["hadronChecks.exemptions"] = true
extra["hadronChecks.staticState"] = listOf("main", "harness")
extra["hadronChecks.determinism"] = listOf<String>()
apply(from = rootProject.file("gradle/hadron-checks.gradle.kts"))
