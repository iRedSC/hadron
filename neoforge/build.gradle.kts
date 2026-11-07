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

neoForge {
    version = providers.gradleProperty("neo_version").get()

    parchment {
        mappingsVersion = providers.gradleProperty("parchment_mappings_version").get()
        minecraftVersion = providers.gradleProperty("parchment_minecraft_version").get()
    }

    runs {
        register("server") {
            server()
            programArgument("--nogui")
        }
        // Test mode: a fresh server in build/hadron-test/run that runs the staged suites' @HadronTests and stops.
        // Plugins don't start it directly; their hadronTest task stages a suite and depends on this.
        register("hadronTest") {
            server()
            programArgument("--nogui")
            gameDirectory = layout.buildDirectory.dir("hadron-test/run")
            systemProperty("hadron.test.report", layout.buildDirectory.file("hadron-test/report.txt").get().asFile.absolutePath)
            disableIdeRun()
        }
    }

    mods {
        register("hadron") {
            sourceSet(project(":api").sourceSets.main.get())
            sourceSet(project(":common").sourceSets.main.get())
            sourceSet(project(":test-api").sourceSets.main.get())
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":test-api"))
    // plugin.yml and plugins' config.yml. Minecraft doesn't ship a YAML parser, so it rides inside Hadron's jar.
    // One version everywhere: the same one api compiles against and dev runs use.
    jarJar("org.yaml:snakeyaml") { version { strictly("[2.2,3)"); prefer("2.2") } }
    // Dev runs don't unpack jarJar, so the game needs it on its classpath there too.
    "additionalRuntimeClasspath"("org.yaml:snakeyaml:2.2")
}

// api and common ship inside the mod's own jar, so server owners install one file.
tasks.jar {
    from(project(":api").sourceSets.main.get().output)
    from(project(":common").sourceSets.main.get().output)
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

// The test server starts from nothing every run: no world, no plugins but the staged suites, nothing listening on the
// dev server's ports (the game port is any free one, RCON and query are off), and no watchdog, since a test holds the
// server thread for the whole run. Suites are moved in, not copied, so a suite staged once runs once.
val hadronTestDir = layout.buildDirectory.dir("hadron-test")
tasks.named<JavaExec>("runHadronTest") {
    description = "Runs the @HadronTests of every staged plugin suite; use a plugin's hadronTest task instead."
    // Each suite's hadronTest task reads the report and fails the build; a failed run still writes one.
    isIgnoreExitValue = true
    // Stage first, whichever projects asked for tests in this build.
    mustRunAfter(provider { rootProject.subprojects.mapNotNull { it.tasks.findByName("hadronTestStage") } })
    val root = hadronTestDir.get().asFile
    doFirst {
        val run = root.resolve("run")
        run.deleteRecursively()
        root.resolve("report.txt").delete()
        val plugins = run.resolve("plugins")
        plugins.mkdirs()
        root.resolve("suites").listFiles { f -> f.name.endsWith(".jar") }?.forEach { jar ->
            jar.copyTo(plugins.resolve(jar.name), overwrite = true)
            jar.delete()
        }
        run.resolve("eula.txt").writeText("eula=true\n")
        run.resolve("server.properties").writeText(
            """
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
        )
    }
}
