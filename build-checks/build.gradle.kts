// Hadron's own Error Prone checks, run by gradle/hadron-checks.gradle.kts on Hadron's modules and on plugins' builds,
// plus the annotations that grant a reasoned exemption. Consumers take it compileOnly: the annotations are SOURCE
// retained, so nothing here ships in a jar or loads at runtime.
plugins {
    `java-library`
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
repositories { mavenCentral() }

val errorProneVersion = "2.52.0"

dependencies {
    compileOnly("com.google.errorprone:error_prone_check_api:$errorProneVersion")
    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")

    testImplementation("com.google.errorprone:error_prone_check_api:$errorProneVersion")
    testImplementation("com.google.errorprone:error_prone_test_helpers:$errorProneVersion")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Error Prone reaches into javac's internals; Java 16+ hides them unless opened.
val javacExports = listOf("api", "code", "comp", "file", "main", "model", "parser", "processing", "tree", "util")
    .map { "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" } +
    "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"

tasks.withType<JavaCompile>().configureEach {
    options.isFork = true
    options.forkOptions.jvmArgs!!.addAll(javacExports)
    options.compilerArgs.addAll(javacExports.filter { it.startsWith("--add-exports") })
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(javacExports)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
