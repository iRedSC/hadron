package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Test mode: a server started with {@code -Dhadron.test.report=<file>} runs every {@code @HadronTest} in the plugins it
 * loaded, writes the report there, and stops, exiting 0 only if every test passed. {@code ./gradlew hadronTest} starts
 * it that way.
 *
 * <p>A plugin jar is a test suite when it carries {@code META-INF/hadron-tests.txt}, the list of its test classes
 * (the build writes it from {@code src/test/java}). The tests run in one task on the server thread, after the first
 * tick, each in its own world; the server's loop waits for them, and they move time on themselves.
 */
final class TestRunner {
    static final String REPORT_PROPERTY = "hadron.test.report";
    static final String INDEX = "META-INF/hadron-tests.txt";
    private static final Logger LOG = LoggerFactory.getLogger("Hadron tests");

    private static volatile int exitCode = 1;

    private final MinecraftServer server;
    private final File reportFile;
    private final TestReport report = new TestReport();
    private final Watchdog watchdog;
    private SocketAddress address;
    private int worlds;

    private TestRunner(MinecraftServer server, File reportFile) {
        this.server = server;
        this.reportFile = reportFile;
        this.watchdog = new Watchdog(report, reportFile, server);
    }

    /** From the mod's constructor; does nothing unless the server was started in test mode. */
    static void register() {
        String report = System.getProperty(REPORT_PROPERTY);
        if (report == null || report.isBlank()) return;
        File file = new File(report).getAbsoluteFile();
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            MinecraftServer server = e.getServer();
            // Queued, so it runs from the loop between ticks, after the first one, with plugins enabled.
            server.tell(new TickTask(server.getTickCount(), () -> new TestRunner(server, file).run()));
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            int code = exitCode;
            // Not from the server thread: exiting runs the shutdown hook, which waits for that thread to end.
            Thread exit = new Thread(() -> System.exit(code), "Hadron test exit");
            exit.setDaemon(false);
            exit.start();
        });
    }

    private void run() {
        watchdog.start();
        try {
            address = server.getConnection().startMemoryChannel();
            List<File> jars = suites();
            for (File jar : jars) runSuite(jar);
        } catch (Throwable t) {
            report.problem("The test run itself failed, so later tests didn't run: " + stack(t));
        } finally {
            watchdog.stop();
            report.write(reportFile);
            LOG.info("\n{}\nReport written to {}", report.render(), reportFile);
            exitCode = report.passed() ? 0 : 1;
            server.halt(false);
        }
    }

    private List<File> suites() {
        File folder = Hadron.getServer().getPluginsFolder();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".jar"));
        List<File> out = new ArrayList<>();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            try (JarFile jar = new JarFile(f)) {
                if (jar.getEntry(INDEX) != null) out.add(f);
            } catch (IOException e) {
                report.problem(f.getName() + " in plugins/ isn't a readable jar: " + e.getMessage());
            }
        }
        return out;
    }

    private void runSuite(File file) throws IOException {
        String pluginName;
        List<String> classNames;
        try (JarFile jar = new JarFile(file)) {
            var yml = jar.getEntry("plugin.yml");
            if (yml == null) {
                report.broken(report.suite(file.getName(), "?"), "it has test classes but no plugin.yml, so no plugin "
                        + "to test; build it from a plugin project, whose resources hold plugin.yml");
                return;
            }
            try (InputStream in = jar.getInputStream(yml)) {
                Object parsed = new Yaml().load(in);
                pluginName = parsed instanceof Map<?, ?> m && m.get("name") != null ? m.get("name").toString() : "?";
            }
            try (InputStream in = jar.getInputStream(jar.getEntry(INDEX))) {
                classNames = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                        .filter(s -> !s.isEmpty()).toList();
            }
        }
        TestReport.Suite suite = report.suite(file.getName(), pluginName);
        JavaPlugin plugin = Hadron.getPluginManager().getPlugin(pluginName);
        if (plugin == null || !Hadron.getPluginManager().isPluginEnabled(pluginName)) {
            report.broken(suite, pluginName + " didn't enable, so none of its tests ran. The server log ("
                    + new File("logs/latest.log").getAbsolutePath() + ") says why; search it for " + pluginName + ".");
            return;
        }
        List<Case> cases = new ArrayList<>();
        for (String name : classNames) {
            Class<?> type;
            try {
                type = Class.forName(name, false, plugin.getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                report.add(suite, fail(name, null, "Couldn't load test class " + name + ": " + e
                        + ". It must compile against the plugin, Hadron's api and test-api only.", null));
                continue;
            }
            for (Method m : type.getDeclaredMethods()) {
                HadronTest test = m.getAnnotation(HadronTest.class);
                if (test != null) cases.add(new Case(type, m, test));
            }
        }
        if (cases.isEmpty()) {
            report.broken(suite, "it has no @HadronTest methods. Add one to a class in src/test/java: "
                    + "@HadronTest void name(TestContext t) { ... }");
            return;
        }
        cases.sort(Comparator.comparing(Case::id));
        for (Case c : cases) {
            report.add(suite, runCase(suite, plugin, c));
            if (!server.isRunning()) {
                report.problem("The server stopped during " + c.id() + ", so the tests after it didn't run. A "
                        + "test or plugin ran /stop or called halt.");
                return;
            }
        }
    }

    private record Case(Class<?> type, Method method, HadronTest test) {
        String id() {
            return type.getSimpleName() + "." + method.getName();
        }
    }

    private TestReport.Result runCase(TestReport.Suite suite, JavaPlugin plugin, Case c) {
        String id = c.id();
        String misuse = misuse(c);
        if (misuse != null) return fail(id, null, misuse, null);
        long start = System.currentTimeMillis();
        TestWorld world;
        try {
            world = new TestWorld(server, plugin, address, id, ++worlds, c.test().timeout());
        } catch (RuntimeException e) {
            return fail(id, null, "Couldn't create the test's world, so it didn't run: " + e, stack(e));
        }
        watchdog.watch(suite, id, c.test().timeout());
        TestReport.Result result;
        try {
            Object instance = null;
            if (!Modifier.isStatic(c.method().getModifiers())) {
                Constructor<?> ctor = c.type().getDeclaredConstructor();
                ctor.setAccessible(true);
                instance = ctor.newInstance();
            }
            c.method().setAccessible(true);
            c.method().invoke(instance, world);
            result = new TestReport.Result(id, TestReport.Status.PASS, null, null, null, world.ticksSpent(),
                    System.currentTimeMillis() - start);
        } catch (InvocationTargetException e) {
            result = failure(id, c.type(), e.getCause());
        } catch (Throwable t) {
            result = failure(id, c.type(), t);
        }
        watchdog.watch(suite, id + " (cleanup)", 200);
        try {
            world.close();
        } catch (RuntimeException e) {
            if (result.status() == TestReport.Status.PASS) result = fail(id, null, e.getMessage(), null);
        } finally {
            watchdog.unwatch();
        }
        if (result.status() == TestReport.Status.PASS) {
            String unhealthy = unhealthy(world);
            if (unhealthy != null) result = fail(id, null, "The test passed but left the server unhealthy: " + unhealthy, null);
        }
        LOG.info("{} {}", result.status(), id);
        return result;
    }

    /** A test method the runner can't call, with the fix; null if it's fine. */
    private static String misuse(Case c) {
        Method m = c.method();
        String shown = c.type().getSimpleName() + "." + m.getName() + "(" + Arrays.stream(m.getParameterTypes())
                .map(Class::getSimpleName).collect(Collectors.joining(", ")) + ")";
        String example = "@HadronTest void " + m.getName() + "(TestContext t) { ... }";
        if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != TestContext.class) {
            return shown + " has @HadronTest but doesn't take exactly one TestContext; write it as " + example;
        }
        if (m.getReturnType() != void.class) {
            return shown + " has @HadronTest but returns " + m.getReturnType().getSimpleName()
                    + "; tests return nothing: " + example;
        }
        if (c.test().timeout() < 1) {
            return shown + " has @HadronTest(timeout = " + c.test().timeout() + "); the limit is in ticks and must be "
                    + "at least 1. Leave it out for the default of 600.";
        }
        if (!Modifier.isStatic(m.getModifiers())) {
            if (Modifier.isAbstract(c.type().getModifiers())) {
                return c.type().getSimpleName() + " is abstract, so its @HadronTest methods have no instance to run "
                        + "on; make the class concrete or the methods static";
            }
            if (c.type().isMemberClass() && !Modifier.isStatic(c.type().getModifiers())) {
                return c.type().getSimpleName() + " is an inner class, which can't be made on its own; declare it "
                        + "static class " + c.type().getSimpleName();
            }
            try {
                c.type().getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                return c.type().getSimpleName() + " has @HadronTest methods but no constructor without arguments, "
                        + "so the runner can't make one; remove its constructors' parameters or add "
                        + c.type().getSimpleName() + "() {}";
            }
        }
        return null;
    }

    private String unhealthy(TestWorld world) {
        if (!server.isRunning()) return "the server stopped";
        List<String> online = new ArrayList<>();
        for (var p : world.players()) if (server.getPlayerList().getPlayer(p.uuid()) != null) online.add(p.name());
        return online.isEmpty() ? null : String.join(", ", online) + " still online after the test";
    }

    private static TestReport.Result failure(String id, Class<?> type, Throwable t) {
        String location = location(type, t);
        if (t instanceof AssertionError) {
            String message = t.getMessage() != null ? t.getMessage() : "An assertion failed without a message.";
            return fail(id, location, message, null);
        }
        String message = "Threw " + t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        return fail(id, location, message, stack(t));
    }

    private static TestReport.Result fail(String id, String location, String message, String detail) {
        return new TestReport.Result(id, TestReport.Status.FAIL, location, message, detail, 0, 0);
    }

    /** The test's own line nearest the failure: {@code ShopTests.java:12}. */
    private static String location(Class<?> type, Throwable t) {
        if (type == null) return null;
        String name = type.getName();
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            for (StackTraceElement e : cause.getStackTrace()) {
                if (e.getClassName().equals(name) || e.getClassName().startsWith(name + "$")) {
                    return (e.getFileName() != null ? e.getFileName() : type.getSimpleName()) + ":" + e.getLineNumber();
                }
            }
        }
        return null;
    }

    /** The stack, cut where the runner called the test, so it shows only the test's and the server's frames. */
    static String stack(Throwable t) {
        StringBuilder out = new StringBuilder();
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause != t) out.append("Caused by: ").append(cause).append('\n');
            else out.append(cause).append('\n');
            int shown = 0;
            for (StackTraceElement e : cause.getStackTrace()) {
                if (e.getClassName().startsWith("jdk.internal.reflect") || e.getClassName().equals(TestRunner.class.getName())
                        || e.getClassName().equals("java.lang.reflect.Method")) break;
                if (shown++ >= 25) {
                    out.append("  ...\n");
                    break;
                }
                out.append("  at ").append(e).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Catches a test that never returns (a loop that doesn't tick, a deadlock), which the tick limit can't: past a
     * wall-clock limit it records the test as hung with where the server thread was, writes the report and ends the
     * process, since the server thread can't be stopped.
     */
    private static final class Watchdog {
        private final TestReport report;
        private final File reportFile;
        private final MinecraftServer server;
        private volatile String current;
        private volatile TestReport.Suite suite;
        private volatile long deadline;
        private volatile boolean running = true;
        private Thread thread;
        private Thread serverThread;

        Watchdog(TestReport report, File reportFile, MinecraftServer server) {
            this.report = report;
            this.reportFile = reportFile;
            this.server = server;
        }

        void start() {
            serverThread = Thread.currentThread();
            thread = new Thread(this::loop, "Hadron test watchdog");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
        }

        /** A generous wall-clock limit: the machine may be slow, so it's far above what the ticks should take. */
        void watch(TestReport.Suite suite, String test, int ticks) {
            this.suite = suite;
            deadline = System.nanoTime() + (120_000L + ticks * 100L) * 1_000_000L;
            current = test;
        }

        void unwatch() {
            current = null;
        }

        private void loop() {
            while (running) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                String test = current;
                if (test == null || System.nanoTime() < deadline) continue;
                StringBuilder where = new StringBuilder();
                int n = 0;
                for (StackTraceElement e : serverThread.getStackTrace()) {
                    if (n++ >= 30) break;
                    where.append("  at ").append(e).append('\n');
                }
                report.add(suite, new TestReport.Result(test, TestReport.Status.HUNG, null, test + " never finished: "
                        + "it ran past its wall-clock limit without returning, so the tick limit couldn't stop it. A "
                        + "loop that never calls t.tick() or t.await(), or a wait on another thread, does this. The "
                        + "tests after it didn't run. The server thread was at:", where.toString(), 0, 0));
                report.write(reportFile);
                LOG.error("\n{}\nReport written to {}", report.render(), reportFile);
                Runtime.getRuntime().halt(3);
            }
        }
    }
}
