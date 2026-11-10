package dev.iredsc.hadron.selftest.hostile;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.economy.Economy;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.io.File;
import java.util.List;

/**
 * Hadron's promise about broken plugins: one never breaks the server or another plugin, and every error it causes
 * says what went wrong and how to fix it. Each test drops a broken jar beside {@link HealthyPlugin}, then checks the
 * message an owner gets (announced to ops, or in the log) and that the server and the healthy plugin still work.
 */
public final class HostileTests {
    private static final String JAR = "selftest-hostile.jar";

    // ---- plugin.yml ----

    /** A jar whose plugin.yml Hadron can't use: the announced refusal must name the jar, the problem and the fix. */
    private static void refusedYml(TestContext t, String yml, String... says) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.jar(JAR, yml, Hostile.HOSTILE_CLASSES);
            String line = h.awaitAnnounced(200, JAR);
            h.assertSays(line, says);
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void pluginYmlThatIsNotYamlIsRefusedSayingWhere(TestContext t) throws Exception {
        refusedYml(t, "name: SelfTestHostile\nmain: [unclosed\n", JAR + " isn't a usable plugin",
                "isn't valid YAML", "line 2");
    }

    @HadronTest(timeout = 1200)
    void pluginYmlWithoutANameIsRefusedSayingWhatToWrite(TestContext t) throws Exception {
        refusedYml(t, "version: \"1\"\nmain: " + HostilePlugin.class.getName() + "\n", "plugin.yml needs 'name'",
                "such as name: ");
    }

    @HadronTest(timeout = 1200)
    void pluginYmlWithoutAMainIsRefusedSayingWhatToWrite(TestContext t) throws Exception {
        refusedYml(t, "name: SelfTestHostile\nversion: \"1\"\n", "plugin.yml needs 'main'", "such as main: ");
    }

    @HadronTest(timeout = 1200)
    void pluginYmlWithAListWhereOneValueGoesIsRefusedSayingWhatToWrite(TestContext t) throws Exception {
        refusedYml(t, "name: [SelfTestHostile, Other]\nversion: \"1\"\nmain: x.Y\n",
                "plugin.yml 'name' must be a single value", "such as name: ");
    }

    @HadronTest(timeout = 1200)
    void pluginYmlWithAMapWhereAListGoesIsRefusedSayingWhatToWrite(TestContext t) throws Exception {
        refusedYml(t, "name: SelfTestHostile\nversion: \"1\"\nmain: x.Y\ndepend: {Other: yes}\n",
                "plugin.yml 'depend' must be a list", "such as depend: [");
    }

    @HadronTest(timeout = 1200)
    void pluginYmlWithAnUnquotedNumericVersionIsRefusedSayingToQuoteIt(TestContext t) throws Exception {
        refusedYml(t, "name: SelfTestHostile\nversion: 1.10\nmain: x.Y\n", "'version' must be quoted",
                "Write version: \"1.1\"");
    }

    // ---- reaching into the host ----

    /**
     * A plugin built without Hadron's checks calls what only the host may: claiming the HostKey, clearing the running
     * server and economy, replacing the packet backend, calling into Hadron's host itself, outside its api, and
     * pushing an actor through the port's unchecked handle. Each is refused, saying what to use, and nothing changes.
     */
    @HadronTest(timeout = 1200)
    void aPluginCanNotReplaceOrClearWhatTheHostInstalled(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            Server server = Hadron.getServer();
            Economy economy = Economy.Lifecycle.running();
            h.mode("host");
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "");
            t.await("the hostile plugin to enable", () -> h.plugins().isPluginEnabled(Hostile.HOSTILE), 200);
            List<String> tried = h.hostileLog.stream().map(String::valueOf).filter(l -> l.startsWith("host "))
                    .toList();
            t.assertEquals(6, tried.size(), "one line per call the plugin tried, in " + tried);
            for (String line : tried) {
                t.assertTrue(line.contains(" refused: "), "a plugin's host-only call must be refused: " + line);
            }
            h.assertSays(tried.get(0), "host claim refused", "HostKey.claim() is for Hadron's own mod",
                    "Hadron.getServer()", "@HadronTest");
            h.assertSays(tried.get(1), "Hadron.Lifecycle.install is for Hadron's own host", "Hadron.getServer()");
            h.assertSays(tried.get(2), "Economy.Lifecycle.install is for Hadron's own host", "Economy.get()");
            h.assertSays(tried.get(3), "NetBackend.Port.install is for Hadron's own host", "Packets");
            h.assertSays(tried.get(4), "dev.iredsc.hadron.common.PluginHost is inside Hadron's plugin host",
                    "only Hadron's api (dev.iredsc.hadron.api)");
            h.assertSays(tried.get(5), "Actors.Port.enter is for Hadron's own host", "Actors.push(actor)");
            t.assertTrue(Hadron.getServer() == server, "the running server must be the host's still");
            t.assertTrue(Economy.Lifecycle.running() == economy, "the economy must be the host's still");
            h.assertHealthy();
        }
    }

    // ---- the main class ----

    /** A jar Hadron reads but can't start: the announcement names the plugin, the problem and the fix. */
    private static void failedToStart(TestContext t, Class<?> main, String mode, String... says) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            if (mode != null) h.mode(mode);
            h.hostile(JAR, Hostile.HOSTILE, main, "");
            String line = h.awaitAnnounced(200, Hostile.HOSTILE + " didn't enable");
            h.assertSays(line, says);
            t.assertFalse(h.plugins().isPluginEnabled(Hostile.HOSTILE), "a plugin that failed to start isn't enabled");
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void aMainClassThatIsNotInTheJarSaysToCheckMain(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.jar(JAR, h.yml(Hostile.HOSTILE, "dev.iredsc.hadron.selftest.hostile.Missing"), Hostile.HOSTILE_CLASSES);
            String line = h.awaitAnnounced(200, Hostile.HOSTILE + " didn't enable");
            h.assertSays(line, "dev.iredsc.hadron.selftest.hostile.Missing isn't in the jar",
                    "full name, package included");
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void aMainClassThatIsNotAJavaPluginSaysToExtendIt(TestContext t) throws Exception {
        failedToStart(t, NotAPlugin.class, null, NotAPlugin.class.getName() + " doesn't extend JavaPlugin",
                "extends dev.iredsc.hadron.api.plugin.JavaPlugin");
    }

    @HadronTest(timeout = 1200)
    void aMainClassWithoutANoArgumentConstructorSaysToAddOne(TestContext t) throws Exception {
        failedToStart(t, NoDefaultConstructorPlugin.class, null, NoDefaultConstructorPlugin.class.getName(),
                "needs a public constructor that takes no arguments");
    }

    // ---- throwing at each step ----

    @HadronTest(timeout = 1200)
    void aConstructorThatThrowsSaysSoAndWhere(TestContext t) throws Exception {
        failedToStart(t, HostilePlugin.class, "constructor",
                "its constructor threw java.lang.IllegalStateException: hostile constructor", "HostilePlugin.java:");
    }

    @HadronTest(timeout = 1200)
    void anOnLoadThatThrowsSaysSoAndWhere(TestContext t) throws Exception {
        failedToStart(t, HostilePlugin.class, "load", "onLoad threw java.lang.IllegalStateException: hostile onLoad",
                "HostilePlugin.java:");
    }

    @HadronTest(timeout = 1200)
    void anOnEnableThatThrowsSaysSoAndWhere(TestContext t) throws Exception {
        failedToStart(t, HostilePlugin.class, "enable",
                "onEnable threw java.lang.IllegalStateException: hostile onEnable", "HostilePlugin.java:");
    }

    @HadronTest(timeout = 1200)
    void anOnDisableThatThrowsStillUnloadsThePluginAndLogsWhere(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.mode("disable");
            File jar = h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "commands:\n  hostile: {}\n");
            t.await("the hostile plugin to enable", () -> h.plugins().isPluginEnabled(Hostile.HOSTILE), 200);
            h.remove(jar, Hostile.HOSTILE);
            t.assertTrue(h.hostileLog.contains("disabled 1"), "its onDisable should have run, but the log is "
                    + h.hostileLog);
            List<String> logged = h.log.matching(Hostile.HOSTILE + " threw while disabling", "hostile onDisable",
                    "HostilePlugin.java:");
            t.assertEquals(1, logged.size(), "server log lines saying the onDisable threw, with its stack");
            t.assertTrue(t.server().getCommands().getDispatcher().getRoot().getChild("hostile") == null,
                    "a plugin whose onDisable threw should still lose its commands, but /hostile is still there");
            h.assertHealthy();
        }
    }

    // ---- throwing while running ----

    @HadronTest(timeout = 1200)
    void aListenerThatThrowsOnEveryEventCostsOnlyItsOwnEvents(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.mode("listener");
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "");
            t.await("the hostile plugin to enable", () -> h.plugins().isPluginEnabled(Hostile.HOSTILE), 200);
            // Events of every kind: chat, a command, block changes.
            for (int i = 0; i < 20; i++) h.admin.chat("noise " + i);
            t.setBlock(t.pos(0, 1, 0), net.minecraft.world.level.block.Blocks.STONE);
            h.admin.breakBlock(t.pos(0, 1, 0));
            h.assertHealthy();
            List<String> logged = h.log.matching(Hostile.HOSTILE + ": HostileListener.on threw on");
            t.assertTrue(!logged.isEmpty() && logged.getFirst().contains("hostile listener on"),
                    "the server log should say which listener threw on which event, with its stack, but has "
                            + logged);
            t.assertTrue(logged.size() <= 6, "a listener that throws on every event should be logged in full a few "
                    + "times, then counted, so it can't flood the log, but it was logged " + logged.size() + " times");
        }
    }

    @HadronTest(timeout = 1200)
    void aCommandThatThrowsTellsTheSenderAndLogsWhere(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "commands:\n  hostile: {}\n");
            t.await("the hostile plugin to enable", () -> h.plugins().isPluginEnabled(Hostile.HOSTILE), 200);
            h.admin.command("hostile");
            t.assertChat(h.admin, "/hostile failed: java.lang.IllegalStateException: hostile command");
            t.assertTrue(!h.log.matching("/hostile", "threw", "HostilePlugin.java:").isEmpty(),
                    "the server log should have /hostile's stack trace");
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void aCommandThatThrowsAnErrorTellsTheSenderAndLeavesThemConnected(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "commands:\n  hostile: {}\n");
            t.await("the hostile plugin to enable", () -> h.plugins().isPluginEnabled(Hostile.HOSTILE), 200);
            h.admin.command("hostile error");
            t.assertTrue(h.admin.isConnected(), "a command whose executor throws an Error should fail that command, "
                    + "not the sender's connection, but admin was disconnected: " + h.admin.disconnectReason());
            t.assertChat(h.admin, "/hostile failed: java.lang.AssertionError: hostile command error");
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void aTaskThatThrowsEveryTickIsLoggedWithoutFloodingTheLog(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.mode("task");
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "");
            t.await("the hostile task to run", () -> h.hostileLog.contains("task ran"), 200);
            t.tick(100);
            h.assertHealthy();
            List<String> logged = h.log.matching("[" + Hostile.HOSTILE + "]", "threw", "hostile task");
            t.assertTrue(!logged.isEmpty() && logged.getFirst().contains("HostilePlugin.java:"),
                    "the server log should say which task threw, with its stack, but has " + logged);
            t.assertTrue(logged.size() <= 6, "a task that throws every tick should be logged in full a few times, "
                    + "then counted, so it can't flood the log, but it was logged " + logged.size() + " times in "
                    + "about 100 ticks");
        }
    }

    // ---- never returning, and reloading under it ----

    @HadronTest(timeout = 1200)
    void anAsyncTaskThatNeverReturnsDoesNotStopTheUnloadAndIsNamed(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.mode("forever");
            File jar = h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "");
            t.await("the hostile task to start", () -> h.hostileLog.contains("forever started 1"), 200);
            h.remove(jar, Hostile.HOSTILE);
            List<String> logged = h.log.matching(Hostile.HOSTILE + " still had 1 background task(s) running");
            t.assertEquals(1, logged.size(), "server log lines saying a background task outlived the unload");
            h.assertSays(logged.getFirst(), "return once isEnabled() is false");
            t.tick(5);
            t.assertFalse(h.hostileLog.stream().anyMatch(l -> l.toString().startsWith("stale sync task ran")),
                    "a disabled plugin's leftover thread got server-thread work run: " + h.hostileLog);
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1500)
    void replacingAJarWhileItsTaskRunsSwapsInTheNewVersionCleanly(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.mode("reload");
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "");
            t.await("version 1 to start its task", () -> h.hostileLog.contains("forever started 1"), 200);
            t.await("version 1's timer to tick", () -> h.hostileLog.contains("tick 1"), 20);
            // Version 2 lands while version 1's background task is mid-run.
            h.jar(JAR, h.yml(Hostile.HOSTILE, HostilePlugin.class.getName()).replace("version: \"1\"",
                    "version: \"2\""), Hostile.HOSTILE_CLASSES);
            t.await("version 2 to enable", () -> h.hostileLog.contains("enabled 2"), 400);
            t.assertTrue(h.plugins().isPluginEnabled(Hostile.HOSTILE), "version 2 should be enabled");
            int disabled = h.hostileLog.indexOf("disabled 1");
            t.assertTrue(disabled >= 0 && disabled < h.hostileLog.indexOf("enabled 2"), "version 1 should have "
                    + "disabled before version 2 enabled, but the log is " + h.hostileLog);
            h.hostileLog.removeIf(l -> l.equals("tick 1"));
            t.tick(10);
            t.assertFalse(h.hostileLog.contains("tick 1"), "version 1's timer still ticks after version 2 replaced it");
            t.assertTrue(h.hostileLog.contains("tick 2"), "version 2's timer should tick, but the log is "
                    + h.hostileLog);
            t.assertFalse(h.hostileLog.stream().anyMatch(l -> l.toString().startsWith("stale sync task ran")),
                    "version 1's leftover thread got server-thread work run after it was replaced: " + h.hostileLog);
            h.assertHealthy();
        }
    }

    // ---- dependencies ----

    @HadronTest(timeout = 1200)
    void aMissingDependencySaysWhichAndHowToFixIt(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "depend: [SelfTestGhost]\n");
            String line = h.awaitAnnounced(200, Hostile.HOSTILE + " didn't enable");
            h.assertSays(line, "needs SelfTestGhost, which isn't in plugins/", "put SelfTestGhost's jar in plugins/",
                    "softdepend");
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void aMissingBridgeSaysWhichToAdd(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            // The test server has no third-party mod without a bridge, so this names one that isn't installed; the
            // message for an installed mod with no bridge is BridgeDependTest's.
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "bridge-depend: [selftest_absent_mod]\n");
            String line = h.awaitAnnounced(200, Hostile.HOSTILE + " didn't enable");
            h.assertSays(line, "uses Hadron's bridge for the selftest_absent_mod mod",
                    "add selftest_absent_mod and its Hadron bridge to mods/");
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void aBridgeDependOnABridgesOwnIdNamesTheModItBridges(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            // hadron_test is the harness, which bridges hadron_test_target.
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "bridge-depend: [hadron_test]\n");
            String line = h.awaitAnnounced(200, Hostile.HOSTILE + " didn't enable");
            h.assertSays(line, "hadron_test is a bridge itself", "bridge-depend: [hadron_test_target]");
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void aBridgeDependOnCoreSaysToRemoveIt(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "bridge-depend: [neoforge]\n");
            String line = h.awaitAnnounced(200, Hostile.HOSTILE + " didn't enable");
            h.assertSays(line, "core covers neoforge itself", "remove neoforge from bridge-depend");
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void aPluginWhoseBridgeIsInstalledEnables(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "bridge-depend: [hadron_test_target]\n");
            t.await("the plugin needing the harness's bridge to enable",
                    () -> h.plugins().isPluginEnabled(Hostile.HOSTILE), 300);
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void aPluginThatDependsOnItselfSaysHowToFixIt(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.hostile(JAR, Hostile.HOSTILE, HostilePlugin.class, "depend: [" + Hostile.HOSTILE + "]\n");
            String line = h.awaitAnnounced(200, Hostile.HOSTILE + " didn't enable");
            h.assertSays(line, "depends on itself", "remove " + Hostile.HOSTILE + " from its own depend");
            h.assertHealthy();
        }
    }

    @HadronTest(timeout = 1200)
    void twoPluginsThatDependOnEachOtherSayHowToBreakTheCycle(TestContext t) throws Exception {
        try (Hostile h = Hostile.open(t)) {
            h.hostile("selftest-hostile-a.jar", "SelfTestCycleA", HostilePlugin.class, "depend: [SelfTestCycleB]\n");
            h.hostile("selftest-hostile-b.jar", "SelfTestCycleB", HostilePlugin.class, "depend: [SelfTestCycleA]\n");
            String a = h.awaitAnnounced(300, "SelfTestCycleA didn't enable", "each other");
            String b = h.awaitAnnounced(300, "SelfTestCycleB didn't enable", "each other");
            for (String line : List.of(a, b)) {
                h.assertSays(line, "SelfTestCycleA", "SelfTestCycleB", "depend on each other", "softdepend");
            }
            h.assertHealthy();
        }
    }
}
