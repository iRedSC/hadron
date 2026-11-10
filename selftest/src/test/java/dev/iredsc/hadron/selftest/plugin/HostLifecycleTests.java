package dev.iredsc.hadron.selftest.plugin;

import com.mojang.brigadier.suggestion.Suggestion;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.server.PluginDisableEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginManager;
import dev.iredsc.hadron.api.world.Area;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * What the host leaves behind when a plugin stops: no menu of its code still open, no class of it remembered, and
 * nobody told it stopped while it still looks enabled. The plugin is {@link HostProbePlugin}, built into a jar here.
 */
public final class HostLifecycleTests {
    private static final String NAME = "SelfTestHostProbe";
    private static final List<Class<?>> CLASSES =
            List.of(HostProbePlugin.class, HostProbeEvent.class, HostProbeMenu.class);

    private static void writeJar(File jar, String minecraft) throws IOException {
        String yml = "name: " + NAME + "\nversion: \"1\"\nminecraft: " + minecraft + "\nmain: "
                + HostProbePlugin.class.getName() + "\n";
        try (OutputStream file = Files.newOutputStream(jar.toPath()); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write(yml.getBytes(StandardCharsets.UTF_8));
            for (Class<?> c : CLASSES) {
                String classFile = c.getName().replace('.', '/') + ".class";
                zip.putNextEntry(new ZipEntry(classFile));
                try (InputStream in = c.getClassLoader().getResourceAsStream(classFile)) {
                    if (in == null) throw new IOException("the test jar has no " + classFile);
                    in.transferTo(zip);
                }
            }
        }
    }

    private static void deleteTree(File dir) throws IOException {
        if (!dir.exists()) return;
        try (Stream<java.nio.file.Path> walk = Files.walk(dir.toPath())) {
            for (java.nio.file.Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static List<String> traceSuggestions(TestContext t) throws Exception {
        var dispatcher = t.server().getCommands().getDispatcher();
        CommandSourceStack console = t.server().createCommandSourceStack();
        var parse = dispatcher.parse("hadron trace ", console);
        return dispatcher.getCompletionSuggestions(parse).get().getList().stream().map(Suggestion::getText).toList();
    }

    private static void clearProperties() {
        System.getProperties().remove(HostProbePlugin.LOG);
        System.getProperties().remove(HostProbePlugin.OPEN_FOR);
        System.getProperties().remove(HostProbePlugin.FAIL);
    }

    @HadronTest(timeout = 900)
    void aPluginWhoseEnableFailsLeavesNoMenuOfItsOpen(TestContext t) throws Exception {
        TestPlayer alice = t.player("alice");
        List<Object> log = new CopyOnWriteArrayList<>();
        System.getProperties().put(HostProbePlugin.LOG, log);
        System.getProperties().put(HostProbePlugin.OPEN_FOR, alice.player().getGameProfile().getName());
        System.getProperties().put(HostProbePlugin.FAIL, "yes");
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        File folder = t.plugin().getServer().getPluginsFolder();
        File jar = new File(folder, "selftest-hostprobe.jar");
        try {
            writeJar(jar, t.server().getServerVersion());
            t.await("Hadron to load the probe and run its onEnable", () -> log.contains("enabled"), 300);
            t.assertFalse(plugins.isPluginEnabled(NAME), "an onEnable that throws leaves the plugin not enabled");
            t.assertTrue(alice.player().containerMenu == alice.player().inventoryMenu, "the menu the failed "
                    + "plugin opened should be closed with it, but alice still has "
                    + alice.player().containerMenu.getClass().getName() + " open");
        } finally {
            jar.delete();
            t.tick(60);
            clearProperties();
            deleteTree(new File(folder, NAME));
        }
    }

    @HadronTest(timeout = 900)
    @Covers(value = Areas.class, method = "watch")
    void aDisabledPluginsAreasClose(TestContext t) throws Exception {
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        File folder = t.plugin().getServer().getPluginsFolder();
        File jar = new File(folder, "selftest-hostprobe.jar");
        try {
            writeJar(jar, t.server().getServerVersion());
            t.await("Hadron to enable the probe", () -> plugins.isPluginEnabled(NAME), 300);
            JavaPlugin probe = java.util.Objects.requireNonNull(plugins.getPlugin(NAME), "the enabled probe by name");
            Area area = Areas.watch(probe, t.level(), BoundingBox.fromCorners(t.pos(0, 0, 0), t.pos(2, 2, 2)));
            jar.delete();
            t.await("Hadron to disable the probe whose jar was deleted", () -> !plugins.isPluginEnabled(NAME), 200);
            t.assertTrue(!area.isOpen(), "an area should close when the plugin that watched it disables");
            t.assertTrue(!Areas.isWatched(t.level(), t.pos(1, 1, 1)), "nothing is watched once its plugin is gone");
        } finally {
            jar.delete();
            if (plugins.isPluginEnabled(NAME)) t.await("the probe to unload", () -> !plugins.isPluginEnabled(NAME), 200);
            clearProperties();
            deleteTree(new File(folder, NAME));
        }
    }

    /** The plain-text lines of {@code /hadron status}, as an op's console would read them. */
    private static List<String> statusLines(TestContext t, TestPlayer op) {
        op.clearReceived();
        op.command("hadron status");
        return op.chat().stream().filter(l -> l.startsWith("plugin=") || l.startsWith("refused\t")).toList();
    }

    private static String sha256(File file) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
    }

    /**
     * A replacement the host refuses leaves the old version running, so {@code /hadron status} keeps naming the jar
     * that runs (its file version and hash) and reports the refused one on a line of its own: a deploy of the
     * replacement then fails with the refusal, instead of matching an identity that is no file's.
     */
    @HadronTest(timeout = 1200)
    void aRefusedReplacementIsReportedApartFromTheJarStillRunning(TestContext t) throws Exception {
        TestPlayer admin = t.player("admin");
        admin.setOp(true);
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        File folder = t.plugin().getServer().getPluginsFolder();
        File jar = new File(folder, "selftest-hostprobe.jar");
        try {
            writeJar(jar, t.server().getServerVersion());
            t.await("Hadron to enable the probe", () -> plugins.isPluginEnabled(NAME), 300);
            String runningSha = sha256(jar);
            long runningModified = jar.lastModified();

            File partial = new File(folder, "selftest-hostprobe.jar.part");
            try (OutputStream file = Files.newOutputStream(partial.toPath()); ZipOutputStream zip = new ZipOutputStream(file)) {
                zip.putNextEntry(new ZipEntry("not-a-plugin.txt"));
                zip.write("no plugin.yml in this jar".getBytes(StandardCharsets.UTF_8));
            }
            t.assertTrue(partial.setLastModified(runningModified + 60_000), "give the replacement its own file version");
            Files.move(partial.toPath(), jar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            String refusedSha = sha256(jar);
            long refusedModified = jar.lastModified();

            t.await("Hadron to refuse the replacement", () -> statusLines(t, admin).stream()
                    .anyMatch(l -> l.startsWith("refused\t")), 300);
            List<String> lines = statusLines(t, admin);
            t.assertTrue(plugins.isPluginEnabled(NAME), "a refused replacement leaves the old version running");
            String running = lines.stream().filter(l -> l.startsWith("plugin=" + NAME + "\t")).findFirst().orElse("");
            t.assertEquals("plugin=" + NAME + "\tstate=enabled\tjar=selftest-hostprobe.jar\tmodified=" + runningModified
                    + "\tsha256=" + runningSha, running, "the running plugin is named by the jar it loaded, not the "
                    + "refused replacement (modified " + refusedModified + ", sha256 " + refusedSha + "); lines: " + lines);
            String refused = lines.stream().filter(l -> l.startsWith("refused\t")).findFirst().orElse("");
            t.assertTrue(refused.startsWith("refused\tstate=refused\tjar=selftest-hostprobe.jar\tmodified="
                    + refusedModified + "\tsha256=" + refusedSha + "\twhy="), "the refused jar has its own line, with "
                    + "its own file version and hash: " + refused);
            t.assertTrue(refused.contains("changed but isn't usable, so the running version stays"),
                    "and says why: " + refused);
        } finally {
            jar.delete();
            if (plugins.isPluginEnabled(NAME)) t.await("the probe to unload", () -> !plugins.isPluginEnabled(NAME), 200);
            clearProperties();
            deleteTree(new File(folder, NAME));
        }
    }

    /**
     * Every status line is about the jar whose outcome it reports. A plugin that failed before loading (here, a missing
     * dependency) keeps naming its own jar when a replacement with a broken plugin.yml arrives: the failure must not
     * pick up the replacement's file version and hash, or a deploy of the replacement would be told to fix the old
     * jar's dependency instead of its YAML.
     */
    @HadronTest(timeout = 1200)
    void aFailureBeforeLoadingKeepsItsOwnJarWhenABrokenReplacementIsRefused(TestContext t) throws Exception {
        TestPlayer admin = t.player("admin");
        admin.setOp(true);
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        File folder = t.plugin().getServer().getPluginsFolder();
        File jar = new File(folder, "selftest-hostprobe.jar");
        try {
            File partial = new File(folder, "selftest-hostprobe.jar.part");
            String needsMissing = "name: " + NAME + "\nversion: \"1\"\nminecraft: " + t.server().getServerVersion()
                    + "\nmain: " + HostProbePlugin.class.getName() + "\ndepend: [SelfTestMissing]\n";
            writeZip(partial, "plugin.yml", needsMissing);
            Files.move(partial.toPath(), jar.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            t.await("Hadron to fail the jar that needs a missing plugin", () -> statusLines(t, admin).stream()
                    .anyMatch(l -> l.startsWith("plugin=" + NAME + "\tstate=failed")), 300);
            String failedSha = sha256(jar);
            long failedModified = jar.lastModified();

            writeZip(partial, "plugin.yml", "name: " + NAME + "\nmain: [unclosed\n");
            t.assertTrue(partial.setLastModified(failedModified + 60_000), "give the replacement its own file version");
            Files.move(partial.toPath(), jar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            String refusedSha = sha256(jar);
            long refusedModified = jar.lastModified();

            t.await("Hadron to refuse the replacement", () -> statusLines(t, admin).stream()
                    .anyMatch(l -> l.startsWith("refused\t")), 300);
            List<String> lines = statusLines(t, admin);
            String failed = lines.stream().filter(l -> l.startsWith("plugin=" + NAME + "\t")).findFirst().orElse("");
            t.assertTrue(failed.startsWith("plugin=" + NAME + "\tstate=failed\tjar=selftest-hostprobe.jar\tmodified="
                    + failedModified + "\tsha256=" + failedSha + "\twhy="), "the failure is about the jar that failed (modified "
                    + failedModified + ", sha256 " + failedSha + "), not the replacement that arrived after; lines: " + lines);
            t.assertTrue(failed.contains("SelfTestMissing"), "and says why: " + failed);
            String refused = lines.stream().filter(l -> l.startsWith("refused\t")).findFirst().orElse("");
            t.assertTrue(refused.startsWith("refused\tstate=refused\tjar=selftest-hostprobe.jar\tmodified="
                    + refusedModified + "\tsha256=" + refusedSha + "\twhy="), "the refusal is about the replacement: "
                    + refused);
            t.assertFalse(plugins.isPluginEnabled(NAME), "neither jar is running");
        } finally {
            jar.delete();
            t.tick(60);
            clearProperties();
            deleteTree(new File(folder, NAME));
        }
    }

    private static void writeZip(File file, String entry, String text) throws IOException {
        try (OutputStream out = Files.newOutputStream(file.toPath()); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @HadronTest(timeout = 900)
    @Covers(PluginDisableEvent.class)
    @Covers(value = PluginManager.class, method = "isPluginEnabled")
    @Covers(value = PluginManager.class, method = "getPlugin")
    void aDisabledPluginIsGoneWhenItsDisableIsAnnouncedAndItsEventsAreForgotten(TestContext t) throws Exception {
        List<Object> log = new CopyOnWriteArrayList<>();
        System.getProperties().put(HostProbePlugin.LOG, log);
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        List<String> during = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PluginDisableEvent e) {
                if (!e.getPlugin().getName().equals(NAME)) return;
                during.add("enabled=" + plugins.isPluginEnabled(NAME) + " plugin=" + plugins.getPlugin(NAME));
            }
        });
        File folder = t.plugin().getServer().getPluginsFolder();
        File jar = new File(folder, "selftest-hostprobe.jar");
        try {
            writeJar(jar, t.server().getServerVersion());
            t.await("Hadron to enable the probe", () -> plugins.isPluginEnabled(NAME), 300);
            t.assertTrue(traceSuggestions(t).contains("HostProbeEvent"), "/hadron trace should suggest the event the "
                    + "probe fired, but suggested " + traceSuggestions(t));

            jar.delete();
            t.await("Hadron to disable the probe whose jar was deleted", () -> !during.isEmpty(), 200);
            t.assertEquals(List.of("enabled=false plugin=null"), during, "what a PluginDisableEvent listener sees "
                    + "of the plugin that just disabled");
            t.assertFalse(traceSuggestions(t).contains("HostProbeEvent"), "an unloaded plugin's event classes should "
                    + "be forgotten, so its class loader can go, but /hadron trace still suggests HostProbeEvent");
        } finally {
            jar.delete();
            if (plugins.isPluginEnabled(NAME)) t.await("the probe to unload", () -> !plugins.isPluginEnabled(NAME), 200);
            clearProperties();
            deleteTree(new File(folder, NAME));
        }
    }
}
