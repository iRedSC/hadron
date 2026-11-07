// regressionReplay: for every fixed bug in regressions/regressions.toml, undoes the fix in a throwaway worktree and
// checks that its guard tests fail (docs/testing.md, "Regression replay"). The work is scripts/regression_replay.py,
// which takes the shared Gradle lock around each build it starts, so don't run this task itself under that lock.

abstract class RegressionReplay : DefaultTask() {
    @get:Input
    @get:Option(option = "only", description = "Replays only this entry of regressions/regressions.toml; repeat for more.")
    abstract val only: ListProperty<String>

    @get:Internal
    abstract val root: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun replay() {
        val args = listOf("python3", "scripts/regression_replay.py") + only.get().flatMap { listOf("--only", it) }
        exec.exec {
            workingDir = root.get().asFile
            commandLine(args)
        }
    }
}

tasks.register<RegressionReplay>("regressionReplay") {
    group = "verification"
    description = "Undoes each fixed bug's fix in a throwaway worktree and checks its guard tests fail. Slow: release only."
    root.set(layout.projectDirectory)
    // Never up to date: it checks the tests, whose inputs are the whole build.
    outputs.upToDateWhen { false }
}
