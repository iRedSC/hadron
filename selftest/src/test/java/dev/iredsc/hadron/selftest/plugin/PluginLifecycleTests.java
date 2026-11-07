package dev.iredsc.hadron.selftest.plugin;

import com.mojang.brigadier.suggestion.Suggestion;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.server.PluginDisableEvent;
import dev.iredsc.hadron.api.event.server.PluginEnableEvent;
import dev.iredsc.hadron.api.plugin.CommandExecutor;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import dev.iredsc.hadron.api.plugin.PluginDescription;
import dev.iredsc.hadron.api.plugin.PluginManager;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.api.plugin.TabCompleter;
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

/**
 * A plugin as server owners install one: a jar dropped into {@code plugins/} loads, enables, answers its plugin.yml
 * commands and keeps its config, and deleting the jar disables it. The jar is {@link ProbePlugin}, built here.
 */
public final class PluginLifecycleTests {
    private static final String NAME = "SelfTestProbe";

    private static String pluginYml(String minecraft) {
        return String.join("\n",
                "name: " + NAME,
                "version: 1.2",
                "minecraft: " + minecraft,
                "main: " + ProbePlugin.class.getName(),
                "description: Probes Hadron's plugin lifecycle",
                "author: alice",
                "authors: [bob]",
                "website: https://example.invalid/probe",
                "softdepend: [NobodyHasThisPlugin]",
                "commands:",
                "  probe:",
                "    description: Probes",
                "    usage: /<command> <what>",
                "    aliases: [pr]",
                "  guarded:",
                "    permission: selftestprobe.use",
                "permissions:",
                "  selftestprobe.use:",
                "    description: Use the guarded command",
                "    default: op",
                "");
    }

    private static void writeJar(File jar, String minecraft) throws IOException {
        String classFile = ProbePlugin.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(jar.toPath()); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write(pluginYml(minecraft).getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("config.yml"));
            zip.write("greeting: hello\nlimits:\n  homes: 3\n".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("hello.txt"));
            zip.write("hi from the jar".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry(classFile));
            try (InputStream in = ProbePlugin.class.getClassLoader().getResourceAsStream(classFile)) {
                if (in == null) throw new IOException("the test jar has no " + classFile);
                in.transferTo(zip);
            }
        }
    }

    private static void deleteTree(File dir) throws IOException {
        if (!dir.exists()) return;
        try (Stream<java.nio.file.Path> walk = Files.walk(dir.toPath())) {
            for (java.nio.file.Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> suggestions(TestContext t, TestPlayer who, String line) throws Exception {
        var dispatcher = t.server().getCommands().getDispatcher();
        var parse = dispatcher.parse(line, who.player().createCommandSourceStack());
        return dispatcher.getCompletionSuggestions(parse).get().getList().stream().map(Suggestion::getText).toList();
    }

    @HadronTest(timeout = 900)
    @Covers(PluginEnableEvent.class)
    @Covers(PluginDisableEvent.class)
    @Covers(value = JavaPlugin.class, method = "onLoad")
    @Covers(value = JavaPlugin.class, method = "onEnable")
    @Covers(value = JavaPlugin.class, method = "onDisable")
    @Covers(value = JavaPlugin.class, method = "isEnabled")
    @Covers(value = JavaPlugin.class, method = "getName")
    @Covers(value = JavaPlugin.class, method = "getDescription")
    @Covers(value = JavaPlugin.class, method = "getLogger")
    @Covers(value = JavaPlugin.class, method = "getFile")
    @Covers(value = JavaPlugin.class, method = "getDataFolder")
    @Covers(value = JavaPlugin.class, method = "getResource")
    @Covers(value = JavaPlugin.class, method = "getConfig")
    @Covers(value = JavaPlugin.class, method = "saveDefaultConfig")
    @Covers(value = JavaPlugin.class, method = "saveConfig")
    @Covers(value = JavaPlugin.class, method = "reloadConfig")
    @Covers(value = JavaPlugin.class, method = "saveResource")
    @Covers(value = PluginDescription.class, method = "getName")
    @Covers(value = PluginDescription.class, method = "getVersion")
    @Covers(value = PluginDescription.class, method = "getMain")
    @Covers(value = PluginDescription.class, method = "getDescription")
    @Covers(value = PluginDescription.class, method = "getAuthors")
    @Covers(value = PluginDescription.class, method = "getWebsite")
    @Covers(value = PluginDescription.class, method = "getDepend")
    @Covers(value = PluginDescription.class, method = "getSoftDepend")
    @Covers(value = PluginDescription.class, method = "getModDepend")
    @Covers(value = PluginDescription.class, method = "getLoader")
    @Covers(value = PluginDescription.class, method = "getMinecraft")
    @Covers(value = PluginDescription.class, method = "getCommands")
    @Covers(value = PluginDescription.class, method = "getPermissions")
    @Covers(value = PluginDescription.class, method = "getFullName")
    @Covers(value = PluginManager.class, method = "getPlugin")
    @Covers(value = PluginManager.class, method = "getPlugins")
    @Covers(value = PluginManager.class, method = "isPluginEnabled")
    @Covers(value = Server.class, method = "getPluginsFolder")
    @Covers(value = Server.class, method = "getPluginManager")
    void aDroppedJarLoadsEnablesKeepsItsConfigAndUnloadsWhenDeleted(TestContext t) throws Exception {
        List<Object> log = new CopyOnWriteArrayList<>();
        System.getProperties().put(ProbePlugin.LOG, log);
        List<String> lifecycle = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PluginEnableEvent e) {
                if (e.getPlugin().getName().equals(NAME)) lifecycle.add("enabled");
            }

            @EventHandler
            void on(PluginDisableEvent e) {
                if (e.getPlugin().getName().equals(NAME)) lifecycle.add("disabled");
            }
        });
        Server server = t.plugin().getServer();
        PluginManager plugins = server.getPluginManager();
        File folder = server.getPluginsFolder();
        File jar = new File(folder, "selftest-probe.jar");
        File data = new File(folder, NAME);
        String minecraft = t.server().getServerVersion();
        try {
            deleteTree(data);
            writeJar(jar, minecraft);
            t.await("Hadron to notice and enable the new jar", () -> plugins.isPluginEnabled(NAME), 300);
            t.assertEquals(List.of("load enabled=false", "enable enabled=true"), log, "what the plugin saw happen");
            t.assertEquals(List.of("enabled"), lifecycle, "the PluginEnableEvents for it");

            JavaPlugin probe = plugins.getPlugin("selftestprobe"); // case-insensitive
            t.assertTrue(probe != null && probe.getClass().getName().equals(ProbePlugin.class.getName()),
                    "getPlugin should find the probe, but found " + probe);
            t.assertTrue(probe.getClass() != ProbePlugin.class, "the probe should come from its own jar's class "
                    + "loader, not the test's");
            t.assertTrue(plugins.getPlugins().contains(probe), "getPlugins should list the probe");
            t.assertTrue(probe.isEnabled(), "isEnabled while running");
            t.assertEquals(NAME, probe.getName(), "getName");
            t.assertTrue(probe.getLogger() != null, "every plugin has a logger");
            t.assertEquals(jar.getCanonicalFile(), probe.getFile().getCanonicalFile(), "getFile");
            t.assertEquals(data.getCanonicalFile(), probe.getDataFolder().getCanonicalFile(), "getDataFolder");

            PluginDescription d = probe.getDescription();
            t.assertEquals(NAME, d.getName(), "the description's name");
            t.assertEquals("1.2", d.getVersion(), "the version");
            t.assertEquals(ProbePlugin.class.getName(), d.getMain(), "the main class");
            t.assertEquals("Probes Hadron's plugin lifecycle", d.getDescription(), "the description");
            t.assertEquals(List.of("alice", "bob"), d.getAuthors(), "author, then authors");
            t.assertEquals("https://example.invalid/probe", d.getWebsite(), "the website");
            t.assertEquals(List.of(), d.getDepend(), "depend");
            t.assertEquals(List.of("NobodyHasThisPlugin"), d.getSoftDepend(), "softdepend");
            t.assertEquals(List.of(), d.getModDepend(), "mod-depend");
            t.assertEquals(null, d.getLoader(), "the loader of a write-once plugin");
            t.assertEquals(minecraft, d.getMinecraft(), "the Minecraft version");
            t.assertEquals(List.of("probe", "guarded"), List.copyOf(d.getCommands().keySet()), "the commands");
            t.assertEquals("selftestprobe.use", d.getPermissions().get(0).node, "the declared node");
            t.assertEquals(NAME + " v1.2", d.getFullName(), "the full name");

            t.assertEquals("hi from the jar", read(probe.getResource("hello.txt")), "a resource from the jar");
            t.assertEquals(null, probe.getResource("missing.txt"), "a resource the jar doesn't have");
            probe.saveDefaultConfig();
            File config = new File(data, "config.yml");
            t.assertTrue(config.isFile(), "saveDefaultConfig should write config.yml into the data folder");
            t.assertEquals("hello", probe.getConfig().getString("greeting"), "the config's greeting");
            t.assertEquals(3, probe.getConfig().getInt("limits.homes"), "a nested value");
            probe.getConfig().set("greeting", "changed");
            probe.saveConfig();
            t.assertTrue(Files.readString(config.toPath()).contains("changed"), "saveConfig should write the change");
            Files.writeString(config.toPath(), "greeting: edited by hand\n");
            probe.reloadConfig();
            t.assertEquals("edited by hand", probe.getConfig().getString("greeting"), "the greeting after reloadConfig");
            t.assertEquals(3, probe.getConfig().getInt("limits.homes"), "a value only the jar's config.yml has, "
                    + "from the defaults behind it");
            probe.saveResource("hello.txt", false);
            t.assertEquals("hi from the jar", Files.readString(new File(data, "hello.txt").toPath()), "a saved resource");

            jar.delete();
            t.await("Hadron to disable the plugin whose jar was deleted", () -> !plugins.isPluginEnabled(NAME), 200);
            t.assertTrue(log.contains("disable"), "onDisable should run when the jar goes, but the plugin saw " + log);
            t.assertEquals(List.of("enabled", "disabled"), lifecycle, "the plugin's enable and disable events");
            t.assertEquals(null, plugins.getPlugin(NAME), "getPlugin once the jar is gone");
        } finally {
            jar.delete();
            if (plugins.isPluginEnabled(NAME)) t.await("the probe to unload", () -> !plugins.isPluginEnabled(NAME), 200);
            System.getProperties().remove(ProbePlugin.LOG);
            deleteTree(data);
        }
    }

    @HadronTest(timeout = 900)
    @Covers(value = JavaPlugin.class, method = "getCommand")
    @Covers(value = JavaPlugin.class, method = "onCommand")
    @Covers(value = JavaPlugin.class, method = "onTabComplete")
    @Covers(value = CommandExecutor.class, method = "onCommand")
    @Covers(value = TabCompleter.class, method = "onTabComplete")
    @Covers(value = PluginCommand.class, method = "getName")
    @Covers(value = PluginCommand.class, method = "getDescription")
    @Covers(value = PluginCommand.class, method = "getUsage")
    @Covers(value = PluginCommand.class, method = "getAliases")
    @Covers(value = PluginCommand.class, method = "getPermission")
    @Covers(value = PluginCommand.class, method = "getPlugin")
    @Covers(value = PluginCommand.class, method = "getExecutor")
    @Covers(value = PluginCommand.class, method = "setExecutor")
    @Covers(value = PluginCommand.class, method = "getTabCompleter")
    @Covers(value = PluginCommand.class, method = "setTabCompleter")
    @Covers(value = PluginCommand.class, method = "testPermissionSilent")
    void aPluginYmlCommandRunsAndCompletesThroughItsExecutor(TestContext t) throws Exception {
        TestPlayer alice = t.player("alice");
        List<Object> log = new CopyOnWriteArrayList<>();
        System.getProperties().put(ProbePlugin.LOG, log);
        PluginManager plugins = t.plugin().getServer().getPluginManager();
        File folder = t.plugin().getServer().getPluginsFolder();
        File jar = new File(folder, "selftest-probe.jar");
        File data = new File(folder, NAME);
        try {
            writeJar(jar, t.server().getServerVersion());
            t.await("Hadron to enable the probe", () -> plugins.isPluginEnabled(NAME), 300);
            JavaPlugin probe = plugins.getPlugin(NAME);
            PluginCommand command = probe.getCommand("probe");
            t.assertEquals("probe", command.getName(), "the command's name");
            t.assertEquals("Probes", command.getDescription(), "its description");
            t.assertEquals("/probe <what>", command.getUsage(), "its usage, with <command> filled in");
            t.assertEquals(List.of("pr"), command.getAliases(), "its aliases");
            t.assertEquals(null, command.getPermission(), "a command anyone may run has no permission");
            t.assertTrue(command.getPlugin() == probe, "the command's plugin");
            t.assertTrue(command.getExecutor() == probe && command.getTabCompleter() == probe,
                    "a command without its own executor runs through the plugin");

            alice.command("probe one two");
            alice.command("pr three");
            t.assertEquals(List.of("command probe probe [one, two]", "command probe pr [three]"),
                    log.stream().filter(l -> l.toString().startsWith("command")).toList(),
                    "the plugin's onCommand calls, by name and by alias");
            t.assertTrue(suggestions(t, alice, "probe ").containsAll(List.of("alpha", "beta")),
                    "the plugin's onTabComplete should suggest alpha and beta, but suggested "
                            + suggestions(t, alice, "probe "));

            List<String> ran = new ArrayList<>();
            command.setExecutor((sender, cmd, label, args) -> {
                ran.add(label + " " + String.join(" ", args));
                return true;
            });
            command.setTabCompleter((sender, cmd, label, args) -> List.of("gamma"));
            alice.command("probe four");
            t.assertEquals(List.of("probe four"), ran, "what the executor set with setExecutor ran");
            t.assertTrue(suggestions(t, alice, "probe ").contains("gamma"), "the completer set with setTabCompleter "
                    + "should suggest gamma, but got " + suggestions(t, alice, "probe "));
            command.setExecutor(null);
            command.setTabCompleter(null);
            t.assertTrue(command.getExecutor() == probe && command.getTabCompleter() == probe,
                    "setting null should put the plugin back as executor and completer");

            PluginCommand guarded = probe.getCommand("guarded");
            t.assertEquals("selftestprobe.use", guarded.getPermission(), "the guarded command's permission");
            t.assertTrue(guarded.testPermissionSilent(t.server().createCommandSourceStack()),
                    "the console holds every command's permission");
            t.assertFalse(guarded.testPermissionSilent(alice.player().createCommandSourceStack()),
                    "alice isn't an operator, so she lacks an op-default permission");
            try {
                probe.getCommand("nope");
                t.fail("getCommand for a command plugin.yml doesn't declare should throw");
            } catch (IllegalArgumentException e) {
                t.assertTrue(e.getMessage().contains("no command 'nope'") && e.getMessage().contains("commands:"),
                        "the message should name the command and the fix, but was: " + e.getMessage());
            }
        } finally {
            jar.delete();
            t.await("the probe to unload", () -> !plugins.isPluginEnabled(NAME), 200);
            System.getProperties().remove(ProbePlugin.LOG);
            deleteTree(data);
        }
    }
}
