// Compile-time checks: Error Prone's default errors, Hadron's own checks (build-checks/), and NullAway for Hadron's
// code, so a mistake the compiler can see fails the build with a message that says the fix. Applied by Hadron's
// modules and by every plugin build (apply(from = ...) for now; the Hadron Gradle plugin will own it).
//
// Set before applying, in extra:
//   hadronChecks.nullAway     source sets NullAway checks, @Nullable from JSpecify (default: none; Hadron's own
//                             modules list theirs, plugins are left out for now).
//   hadronChecks.staticState  source sets NoStaticMutableState refuses static state in (default: main and test,
//                             since a plugin's tests reload with it in a warm test server).
//   hadronChecks.determinism  source sets Determinism refuses wall-clock time, sleeps and unseeded randomness in
//                             (default: test, where a plugin's tests live).
//   hadronChecks.exemptions   true for Hadron's own code only: @StaticState and @Nondeterministic, each with a
//                             reason, may exempt one field or method. A plugin's build refuses them.
// TooLong prints a warning for a file over 800 lines or a method over 120 and never fails.

@Suppress("UNCHECKED_CAST")
fun <T> setting(name: String, default: T): T = if (extra.has(name)) extra[name] as T else default

val nullAwaySets = setting("hadronChecks.nullAway", listOf<String>())
val staticStateSets = setting("hadronChecks.staticState", listOf("main", "test"))
val determinismSets = setting("hadronChecks.determinism", listOf("test"))
val exemptions = setting("hadronChecks.exemptions", false)

val errorprone = configurations.create("errorprone") {
    isCanBeConsumed = false
    description = "Error Prone, NullAway and Hadron's checks, run inside javac"
}
dependencies {
    "errorprone"("com.google.errorprone:error_prone_core:2.52.0")
    "errorprone"("com.uber.nullaway:nullaway:0.14.3")
    "errorprone"(project(":build-checks"))
}

// Error Prone runs inside javac and reaches into its internals, which Java 16+ hides unless opened.
val javacOpens = listOf("api", "code", "comp", "file", "main", "model", "parser", "processing", "tree", "util")
    .map { "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" } +
    listOf("comp", "tree").map { "--add-opens=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" }

val sourceSets = extensions.getByType<SourceSetContainer>()
sourceSets.configureEach {
    val set = name
    tasks.named<JavaCompile>(compileJavaTaskName) {
        fun on(enabled: Boolean, severity: String = "ERROR") = if (enabled) severity else "OFF"
        val flags = listOf(
            "-Xplugin:ErrorProne",
            // Error Prone's default errors fail the build; its warnings are left out, so what prints is worth reading.
            "-XepDisableAllWarnings",
            "-XepDisableWarningsInGeneratedCode",
            "-Xep:TooLong:WARN",
            "-Xep:NoStaticMutableState:" + on(set in staticStateSets),
            "-Xep:Determinism:" + on(set in determinismSets),
            "-Xep:NullAway:" + on(set in nullAwaySets),
            "-XepOpt:Hadron:AllowExemptions=$exemptions",
            "-XepOpt:NullAway:AnnotatedPackages=dev.iredsc.hadron",
            // Mixin fills these in when it applies the mixin, so they're never null where the code runs.
            "-XepOpt:NullAway:ExcludedFieldAnnotations=org.spongepowered.asm.mixin.Shadow,"
                    + "org.spongepowered.asm.mixin.gen.Accessor",
        )
        options.annotationProcessorPath = (options.annotationProcessorPath ?: files()) + errorprone
        options.compilerArgs.addAll(listOf("-XDcompilePolicy=simple", "--should-stop=ifError=FLOW", "-XDaddTypeAnnotationsToSymbol=true",
            flags.joinToString(" ")))
        options.isFork = true
        options.forkOptions.jvmArgs!!.addAll(javacOpens)
    }
}
