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
