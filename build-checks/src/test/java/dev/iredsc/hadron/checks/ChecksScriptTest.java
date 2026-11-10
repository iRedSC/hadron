package dev.iredsc.hadron.checks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * gradle/hadron-checks.gradle.kts as a plugin's build applies it: a plugin can't switch off the checks that would
 * catch its mistakes, only Hadron's own modules can.
 */
class ChecksScriptTest {
    private static final String SCRIPT = System.getProperty("hadron.checksScript");

    @TempDir
    Path plugin;

    @Test
    void aPluginBuildThatLoosensTheChecksFailsSayingWhatToDoInstead() throws IOException {
        Files.writeString(plugin.resolve("settings.gradle"), "rootProject.name = 'myplugin'\n");
        Files.writeString(plugin.resolve("build.gradle"), String.join("\n",
                "plugins { id 'java' }",
                "ext['hadronChecks.exemptions'] = true",
                "ext['hadronChecks.staticState'] = ['main']",
                "ext['hadronChecks.determinism'] = []",
                "apply from: '" + SCRIPT.replace("\\", "/") + "'",
                ""));
        BuildResult result = GradleRunner.create().withProjectDir(plugin.toFile()).withArguments("help").buildAndFail();
        String out = result.getOutput();
        assertTrue(out.contains("sets hadronChecks.exemptions = true, hadronChecks.staticState without test, "
                + "hadronChecks.determinism without test, but only Hadron's own modules may loosen Hadron's checks"),
                out);
        assertTrue(out.contains("Remove those lines"), out);
        assertTrue(out.contains("instead of a static field"), out);
        assertTrue(out.contains("instead of anything that takes the HostKey"), out);
    }
}
