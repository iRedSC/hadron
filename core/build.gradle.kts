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
    }

    mods {
        register("hadron") {
            sourceSet(sourceSets.main.get())
        }
    }
}

java {
    // Plugin authors' agents read the API from the sources jar, so every release ships one.
    withSourcesJar()
}

dependencies {
    // plugin.yml and plugins' config.yml. Minecraft doesn't ship a YAML parser, so it rides inside Hadron's jar.
    api("org.yaml:snakeyaml:2.2")
    jarJar("org.yaml:snakeyaml:[2.2,3)")
    // Dev runs don't unpack jarJar, so the game needs it on its classpath there too.
    "additionalRuntimeClasspath"("org.yaml:snakeyaml:2.2")
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
