plugins {
    java
    id("net.neoforged.moddev") version "2.0.148"
}

version = "1.0.0"
java.toolchain.languageVersion = JavaLanguageVersion.of(21)
repositories { mavenCentral() }

neoForge {
    version = providers.gradleProperty("neo_version").get()
}

dependencies {
    compileOnly(project(":core"))
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraft" to providers.gradleProperty("minecraft_version").get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

// Drops the jar into the core dev server's plugins/, where Hadron picks it up within a second or two.
tasks.register<Copy>("deploy") {
    from(tasks.jar)
    into(rootProject.layout.projectDirectory.dir("core/run/plugins"))
    rename { "Hello.jar" }
}
