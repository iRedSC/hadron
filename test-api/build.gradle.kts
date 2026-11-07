// What a plugin's tests compile against (src/test/java), and only they: @HadronTest, TestContext, TestPlayer. Vanilla
// Minecraft plus Hadron's api, like api itself, so a test runs on every loader. Kept out of api so a plugin's main
// code can't reach for a test player: the build refuses it.
plugins {
    `java-library`
    id("net.neoforged.moddev") version "2.0.148"
}

version = providers.gradleProperty("hadron_version").get()
java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}
repositories { mavenCentral() }

neoForge {
    neoFormVersion = providers.gradleProperty("neoform_version").get()
}

dependencies {
    api(project(":api"))
    // @WithMinecraft and MinecraftBootstrap, for fast tests run by ./gradlew test. compileOnly: a plugin's test
    // build brings JUnit, and the in-game test server never loads them.
    compileOnly(platform("org.junit:junit-bom:5.13.4"))
    compileOnly("org.junit.jupiter:junit-jupiter-api")
}
