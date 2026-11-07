package dev.iredsc.hadron.selftest.plugin;

import com.mojang.brigadier.suggestion.Suggestion;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.server.PluginDisableEvent;
import dev.iredsc.hadron.api.plugin.PluginManager;
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
