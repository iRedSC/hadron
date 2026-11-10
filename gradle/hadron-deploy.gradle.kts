// deploy for a plugin project: installs the plugin's jar into the running dev server (neoforge/run/plugins/), then
// waits until the server reports it loaded THIS jar and prints one line: enabled, or why it failed and the fix. The
// task fails the build when the plugin failed to load, the jar was refused, the server never picked it up, or no dev
// server answered. Applied with apply(from = ...) for now; the Hadron Gradle plugin will own it, which is why it uses
// nothing but Gradle's own API and project-relative paths.
//
// The work is gradle/deploy/HadronDeploy.java, run by the JDK's source launcher: it asks the host's `hadron status`
// (plain text over RCON) for the exact file version it wrote, matched by content hash and modification time.
//
// Set before applying, in extra (both optional):
//   hadronDeploy.installAs   the file name in plugins/ (default: <project name>.jar). Keep it the same between
//                            builds: the host replaces a plugin of the same name in place.
//   hadronDeploy.serverDir   the dev server's folder (default: neoforge/run in the root project).

@Suppress("UNCHECKED_CAST")
fun <T> deploySetting(name: String, default: T): T = if (extra.has(name)) extra[name] as T else default

abstract class HadronDeployTask : DefaultTask() {
    @get:InputFile
    abstract val jar: RegularFileProperty

    @get:Internal
    abstract val serverDir: DirectoryProperty

    @get:Input
    abstract val installAs: Property<String>

    @get:InputFile
    abstract val client: RegularFileProperty

    @get:Internal
    abstract val java: Property<String>

    init {
        // A deploy is asked for, never up to date: the server may have been restarted or the jar deleted.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun deploy() {
        val process = ProcessBuilder(
            java.get(), client.get().asFile.path,
            "--jar", jar.get().asFile.path,
            "--server-dir", serverDir.get().asFile.path,
            "--install-as", installAs.get(),
        ).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader()
        val err = process.errorStream.bufferedReader()
        // Reading stderr on a thread keeps a full pipe from stalling the client.
        var errors = ""
        val reader = Thread { errors = err.readText() }.also { it.start() }
        val said = out.readText().trim()
        reader.join()
        if (process.waitFor() != 0) {
            throw GradleException(errors.trim().ifEmpty { "The deploy client exited without a reason (code ${process.exitValue()})." })
        }
        logger.lifecycle(said)
    }
}

val java21 = extensions.getByType<JavaToolchainService>().launcherFor { languageVersion = JavaLanguageVersion.of(21) }

tasks.register<HadronDeployTask>("deploy") {
    group = "hadron"
    description = "Installs the jar into the running dev server and waits until it reports this jar enabled."
    val jarTask = tasks.named<Jar>("jar")
    dependsOn(jarTask)
    jar.set(jarTask.flatMap { it.archiveFile })
    serverDir.set(deploySetting("hadronDeploy.serverDir", rootProject.layout.projectDirectory.dir("neoforge/run")))
    installAs.set(deploySetting("hadronDeploy.installAs", "${project.name}.jar"))
    client.set(rootProject.layout.projectDirectory.file("gradle/deploy/HadronDeploy.java"))
    java.set(java21.map { it.executablePath.asFile.path })
}
