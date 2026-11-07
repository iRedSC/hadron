package dev.iredsc.hadron.selftest.hostile;

import dev.iredsc.hadron.api.plugin.PluginManager;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One hostile-plugin test's setup: {@link HealthyPlugin} running beside whatever broken jar the test drops in, an op
 * ({@code admin}) who receives Hadron's announcements as an owner would, and the server log captured. {@link #close}
 * takes every jar it wrote back out and waits until their plugins are gone, so no test leaves a plugin behind.
 */
final class Hostile implements AutoCloseable {
    static final String HEALTHY = "SelfTestHealthy";
    static final String HOSTILE = "SelfTestHostile";
    /** Every class a hostile jar may need; a jar holds them all, as its plugin.yml picks which is main. */
    static final List<Class<?>> HOSTILE_CLASSES =
            List.of(HostilePlugin.class, HostileListener.class, NotAPlugin.class, NoDefaultConstructorPlugin.class);

    final TestContext t;
    final TestPlayer admin;
    final LogCapture log = LogCapture.open();
    final List<Object> hostileLog = new CopyOnWriteArrayList<>();
    final List<Object> healthyLog = new CopyOnWriteArrayList<>();
    final AtomicInteger healthyTicks = new AtomicInteger();
    final AtomicBoolean release = new AtomicBoolean(false);
    private final File folder;
    private final List<File> jars = new ArrayList<>();
    private final List<String> names = new ArrayList<>();

    private Hostile(TestContext t) {
        this.t = t;
        this.admin = t.player("admin");
        admin.setOp(true);
        this.folder = t.plugin().getServer().getPluginsFolder();
        System.getProperties().put(HostilePlugin.LOG, hostileLog);
        System.getProperties().put(HostilePlugin.RELEASE, release);
        System.getProperties().put(HealthyPlugin.LOG, healthyLog);
        System.getProperties().put(HealthyPlugin.TICKS, healthyTicks);
    }

    /** Starts the healthy plugin and waits for it, so each test starts with it running. */
    static Hostile open(TestContext t) throws IOException {
        Hostile h = new Hostile(t);
        try {
            h.jar("selftest-healthy.jar", h.yml(HEALTHY, HealthyPlugin.class.getName()) + "commands:\n  healthy: {}\n",
                    List.of(HealthyPlugin.class));
            h.names.add(HEALTHY);
            t.await("Hadron to enable the healthy plugin", () -> h.plugins().isPluginEnabled(HEALTHY), 300);
        } catch (RuntimeException | Error | IOException e) {
            h.close();
            throw e;
        }
        return h;
    }

    PluginManager plugins() {
        return t.plugin().getServer().getPluginManager();
    }

    /** A plugin.yml's usual lines: its name, a quoted version, this server's Minecraft version and the main class. */
    String yml(String name, String main) {
        return "name: " + name + "\nversion: \"1\"\nminecraft: \"" + t.server().getServerVersion() + "\"\nmain: " + main
                + "\n";
    }

    /** Writes a jar with that plugin.yml and those classes into plugins/, where Hadron finds it within a second. */
    File jar(String fileName, String pluginYml, List<Class<?>> classes) throws IOException {
        File jar = new File(folder, fileName);
        if (!jars.contains(jar)) jars.add(jar);
        File partial = new File(folder, fileName + ".part");
        try (OutputStream file = Files.newOutputStream(partial.toPath()); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write(pluginYml.getBytes(StandardCharsets.UTF_8));
            for (Class<?> c : classes) {
                String classFile = c.getName().replace('.', '/') + ".class";
                zip.putNextEntry(new ZipEntry(classFile));
                try (InputStream in = c.getClassLoader().getResourceAsStream(classFile)) {
                    if (in == null) throw new IOException("the test jar has no " + classFile);
                    in.transferTo(zip);
                }
            }
        }
        // Whole or not at all, as a deploy would: Hadron never sees a half-written jar.
        Files.move(partial.toPath(), jar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return jar;
    }

    /** A hostile jar named {@code name}, its main {@code main}, with extra plugin.yml lines. */
    File hostile(String fileName, String name, Class<?> main, String extraYml) throws IOException {
        if (!names.contains(name)) names.add(name);
        return jar(fileName, yml(name, main.getName()) + extraYml, HOSTILE_CLASSES);
    }

    void mode(String mode) {
        System.getProperties().put(HostilePlugin.MODE, mode);
    }

    /** Waits for Hadron to tell the op, in red, a line containing every one of {@code texts}. @return that line. */
    String awaitAnnounced(int ticks, String... texts) {
        t.await("Hadron to announce a failure containing " + List.of(texts),
                () -> announced(texts) != null, ticks);
        return announced(texts);
    }

    private String announced(String... texts) {
        for (String line : admin.chat()) {
            if (line.startsWith("✖") && Stream.of(texts).allMatch(line::contains)) return line;
        }
        return null;
    }

    /** That the announced line says each of these: what went wrong and how to fix it. */
    void assertSays(String line, String... parts) {
        for (String part : parts) {
            t.assertTrue(line.contains(part), "Hadron's message should say \"" + part + "\", but was: " + line);
        }
    }

    /**
     * The server and the healthy plugin still work: the server ticks, the healthy plugin is enabled, its timer runs,
     * its command answers and its listener hears chat.
     */
    void assertHealthy() {
        t.assertTrue(plugins().isPluginEnabled(HEALTHY), "the healthy plugin should still be enabled beside the "
                + "broken one, but isn't");
        int before = healthyTicks.get();
        t.tick(5);
        t.assertTrue(healthyTicks.get() >= before + 4, "the healthy plugin's timer should run every tick, but ran "
                + (healthyTicks.get() - before) + " times in 5 ticks");
        admin.clearReceived();
        admin.command("healthy");
        t.assertChat(admin, "healthy ok");
        String said = "still fine " + t.ticksSpent();
        admin.chat(said);
        t.tick();
        t.assertTrue(healthyLog.contains("chat " + said), "the healthy plugin's chat listener should hear \"" + said
                + "\", but heard " + healthyLog);
        t.assertTrue(admin.isConnected(), "the op should still be connected, but was disconnected: "
                + admin.disconnectReason());
    }

    /** Takes a plugin's jar out and waits until its plugin is gone. */
    void remove(File jar, String name) throws IOException {
        Files.deleteIfExists(jar.toPath());
        t.await("Hadron to unload " + name + " after its jar was deleted", () -> plugins().getPlugin(name) == null
                && !plugins().isPluginEnabled(name), 400);
    }

    @Override
    public void close() throws IOException {
        release.set(true);
        try {
            for (File jar : jars) Files.deleteIfExists(jar.toPath());
            // A failed plugin has no Plugin object, so "gone" is: no enabled plugin of these names, and two polls passed
            // for the removals to be seen.
            t.await("the test's plugins to unload", () -> names.stream().noneMatch(plugins()::isPluginEnabled), 400);
            t.tick(45);
        } finally {
            for (String name : names) deleteTree(new File(folder, name));
            System.getProperties().remove(HostilePlugin.MODE);
            System.getProperties().remove(HostilePlugin.LOG);
            System.getProperties().remove(HostilePlugin.RELEASE);
            System.getProperties().remove(HealthyPlugin.LOG);
            System.getProperties().remove(HealthyPlugin.TICKS);
            log.close();
        }
    }

    private static void deleteTree(File dir) throws IOException {
        if (!dir.exists()) return;
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
