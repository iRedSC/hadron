package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.neoforge.Hooks;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.IEventBus;
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
 *
 * <p>A server started with {@code -Dhadron.test.watch=<dir>} instead stays up and reruns a suite each time a build
 * stages it again: see {@link TestWatch}. Both run suites through {@link #runSuite}.
 */
final class TestRunner {
    static final String REPORT_PROPERTY = "hadron.test.report";
    static final String INDEX = "META-INF/hadron-tests.txt";
    /** Beside a staged suite jar in plugins/: the {@link TestFilter} patterns its run is limited to. */
    static final String FILTER_SUFFIX = ".tests";
    static final Logger LOG = LoggerFactory.getLogger("Hadron tests");

    @StaticState(reason = "a test server's result for its process exit; one test run per JVM")
    private static volatile int exitCode = 1;

    private final MinecraftServer server;
    final Watchdog watchdog;
    private @Nullable SocketAddress address;
    /** Counts every test world this server made, so a warm server's reruns never reuse a world's name. */
    private int worlds;
    /** The plugin name of every suite this server has seen, so each suite's turn can switch the others off. */
    private final Set<String> suitePlugins = new LinkedHashSet<>();

    TestRunner(MinecraftServer server, Watchdog.OnHang onHang) {
        this.server = server;
        this.watchdog = new Watchdog(onHang);
    }

    /**
     * From {@link HarnessMod}'s constructor; does nothing unless the server was started in test or watch mode. In either, it also
     * registers {@link TestMod}'s channel, so a normal server never has it.
     */
    static void register(IEventBus modBus) {
        String watch = System.getProperty(TestWatch.WATCH_PROPERTY);
        if (watch != null && !watch.isBlank()) {
            TestMod.register(modBus);
            TestWatch.register(new File(watch).getAbsoluteFile());
            return;
        }
        String report = System.getProperty(REPORT_PROPERTY);
        if (report == null || report.isBlank()) return;
        TestMod.register(modBus);
        File file = new File(report).getAbsoluteFile();
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            MinecraftServer server = e.getServer();
            // Queued, so it runs from the loop between ticks, after the first one, with plugins enabled.
            server.tell(new TickTask(server.getTickCount(), () -> runOnce(server, file)));
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> exitLater(exitCode));
    }

    /** Not from the server thread: exiting runs the shutdown hook, which waits for that thread to end. */
    static void exitLater(int code) {
        Thread exit = new Thread(() -> System.exit(code), "Hadron test exit");
        exit.setDaemon(false);
        exit.start();
    }

    /** A cold run: every suite in plugins/, one report, then the server stops. */
    private static void runOnce(MinecraftServer server, File reportFile) {
        TestReport report = new TestReport();
        TestRunner runner = new TestRunner(server, hung -> hung.write(reportFile));
        runner.open(report);
        try {
            List<File> jars = runner.suites(report);
            for (File jar : jars) {
                String name = pluginName(jar);
                if (name != null) runner.suitePlugins.add(name);
            }
            for (File jar : jars) runner.runSuite(report, jar, filterBeside(jar));
        } catch (Throwable t) {
            report.problem("The test run itself failed, so later tests didn't run: " + stack(t));
        } finally {
            runner.watchdog.stop();
            report.write(reportFile);
            LOG.info("\n{}\nReport written to {} and {}", report.render(), reportFile, TestReport.jsonFile(reportFile));
            exitCode = report.passed() ? 0 : 1;
            server.halt(false);
        }
    }

    /** Once per server, on the server thread: the players' connection and the watchdog. */
    void open(TestReport first) {
        watchdog.start(first);
        address = server.getConnection().startMemoryChannel();
    }

    /** Remembers a suite's plugin, so the other suites' turns switch it off. */
    void knowSuite(String pluginName) {
        suitePlugins.add(pluginName);
    }

    private List<File> suites(TestReport report) {
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

    /** The filter staged beside a suite's jar ({@code examples-hello.jar.tests}), or every test. */
    private static TestFilter filterBeside(File jar) throws IOException {
        File file = new File(jar.getPath() + FILTER_SUFFIX);
        return file.isFile() ? TestFilter.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8)) : TestFilter.ALL;
    }

    /** The plugin name in a jar's plugin.yml, or null if it has none. */
    static @Nullable String pluginName(File file) {
        try (JarFile jar = new JarFile(file)) {
            var yml = jar.getEntry("plugin.yml");
            if (yml == null) return null;
            try (InputStream in = jar.getInputStream(yml)) {
                Object parsed = new Yaml().load(in);
                return parsed instanceof Map<?, ?> m && m.get("name") != null ? m.get("name").toString() : null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Runs the tests of one suite jar already in plugins/ (and enabled by the host) that {@code filter} allows. */
    void runSuite(TestReport report, File file, TestFilter filter) throws IOException {
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
        suite.filter = filter.patterns();
        suitePlugins.add(pluginName);
        // Every other suite's plugin is off while this one runs, so their listeners can't change its results.
        Hooks.host().isolateForTest(pluginName, suitePlugins);
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
                if (filter.mightMatchClass(name)) {
                    report.add(suite, new TestReport.Result(name, name, null, TestReport.Status.FAIL, null, null,
                            "Couldn't load test class " + name + ": " + e + ". It must compile against the plugin, "
                                    + "Hadron's api and test-api only.", null, 0, 0));
                }
                continue;
            }
            for (Method m : type.getDeclaredMethods()) {
                HadronTest test = m.getAnnotation(HadronTest.class);
                if (test != null) cases.add(new Case(type, m, test));
            }
        }
        suite.available = cases.size();
        if (cases.isEmpty()) {
            report.broken(suite, "it has no @HadronTest methods. Add one to a class in src/test/java: "
                    + "@HadronTest void name(TestContext t) { ... }");
            return;
        }
        List<Case> chosen = new ArrayList<>();
        for (Case c : cases) if (filter.matches(c.type(), c.method().getName())) chosen.add(c);
        if (chosen.isEmpty()) {
            report.broken(suite, "no test matches " + String.join(", ", filter.patterns()) + ", so none ran. Its "
                    + "tests are " + cases.stream().map(Case::id).sorted().collect(Collectors.joining(", "))
                    + ". Name a class (HelloTests), a method (HelloTests.greets) or use * (HelloTests.greet*).");
            return;
        }
        chosen.sort(Comparator.comparing(Case::id));
        for (Case c : chosen) {
            report.add(suite, runCase(report, suite, plugin, c));
            if (!server.isRunning()) {
                report.problem("The server stopped during " + c.id() + ", so the tests after it didn't run. A "
                        + "test or plugin ran /stop or called halt.");
                return;
            }
            if (Hadron.getPluginManager().getPlugin(pluginName) != plugin) {
                report.problem(pluginName + " was reloaded or disabled while " + c.id() + " ran, so the tests after "
                        + "it didn't run: they would have tested a plugin that's no longer the one loaded. Something "
                        + "replaced its jar in plugins/ directly; rebuild with hadronRerun instead, which waits until "
                        + "no test is running.");
                return;
            }
        }
    }

    private record Case(Class<?> type, Method method, HadronTest test) {
        String id() {
            return type.getSimpleName() + "." + method.getName();
        }
    }

    private TestReport.Result runCase(TestReport report, TestReport.Suite suite, JavaPlugin plugin, Case c) {
        String id = c.id();
        String source = TestSource.of(c.type(), c.method());
        String misuse = misuse(c);
        if (misuse != null) return result(c, source, TestReport.Status.FAIL, null, misuse, null, 0, 0);
        long start = System.currentTimeMillis();
        TestWorld world;
        try {
            world = new TestWorld(server, plugin, address, id, ++worlds, c.test().timeout());
            world.awaitReady();
        } catch (RuntimeException e) {
            return result(c, source, TestReport.Status.FAIL, null, "Couldn't create the test's world, so it didn't "
                    + "run: " + e, stack(e), 0, System.currentTimeMillis() - start);
        }
        watchdog.watch(report, suite, c.type().getName(), c.method().getName(), id, source, c.test().timeout());
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
            result = result(c, source, TestReport.Status.PASS, null, null, null, world.ticksSpent(),
                    System.currentTimeMillis() - start);
        } catch (InvocationTargetException e) {
            Throwable thrown = e.getCause() != null ? e.getCause() : e;
            result = failure(c, source, thrown, world.ticksSpent(), System.currentTimeMillis() - start);
        } catch (Throwable t) {
            result = failure(c, source, t, world.ticksSpent(), System.currentTimeMillis() - start);
        }
        watchdog.watch(report, suite, c.type().getName(), c.method().getName(), id + " (cleanup)", source, 200);
        try {
            world.close();
        } catch (RuntimeException e) {
            if (result.status() == TestReport.Status.PASS) result = again(result, e.getMessage());
        } finally {
            watchdog.unwatch();
        }
        if (result.status() == TestReport.Status.PASS) {
            String unhealthy = unhealthy(world);
            if (unhealthy != null) result = again(result, "The test passed but left the server unhealthy: " + unhealthy);
        }
        LOG.info("{} {}", result.status(), id);
        return result;
    }

    private static TestReport.Result result(Case c, @Nullable String source, TestReport.Status status, @Nullable String location,
                                            @Nullable String message, @Nullable String detail, int ticks, long millis) {
        return new TestReport.Result(c.id(), c.type().getName(), c.method().getName(), status, source, location,
                message, detail, ticks, millis);
    }

    /** A passed test turned into a failure by what came after it (cleanup, health). */
    private static TestReport.Result again(TestReport.Result r, @Nullable String message) {
        return new TestReport.Result(r.id(), r.className(), r.method(), TestReport.Status.FAIL, r.source(), null,
                message, null, r.ticks(), r.millis());
    }

    /** A test method the runner can't call, with the fix; null if it's fine. */
    private static @Nullable String misuse(Case c) {
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
            if (java.util.Arrays.stream(c.type().getDeclaredConstructors()).noneMatch(k -> k.getParameterCount() == 0)) {
                return c.type().getSimpleName() + " has @HadronTest methods but no constructor without arguments, "
                        + "so the runner can't make one; remove its constructors' parameters or add "
                        + c.type().getSimpleName() + "() {}";
            }
        }
        return null;
    }

    private @Nullable String unhealthy(TestWorld world) {
        if (!server.isRunning()) return "the server stopped";
        List<String> online = new ArrayList<>();
        for (var p : world.players()) if (server.getPlayerList().getPlayer(p.uuid()) != null) online.add(p.name());
        return online.isEmpty() ? null : String.join(", ", online) + " still online after the test";
    }

    private static TestReport.Result failure(Case c, @Nullable String source, Throwable t, int ticks, long millis) {
        String location = location(c.type(), t);
        if (t instanceof AssertionError) {
            String message = t.getMessage() != null ? t.getMessage() : "An assertion failed without a message.";
            return result(c, source, TestReport.Status.FAIL, location, message, null, ticks, millis);
        }
        String message = "Threw " + t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        return result(c, source, TestReport.Status.FAIL, location, message, stack(t), ticks, millis);
    }

    /** The test's own line nearest the failure: {@code ShopTests.java:12}. */
    private static @Nullable String location(Class<?> type, @Nullable Throwable t) {
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
     * wall-clock limit it records the test as hung with where the server thread was, has the report written and ends
     * the process, since the server thread can't be stopped.
     */
    static final class Watchdog {
        /** Writes the report that holds the hung test; the process ends right after. */
        interface OnHang {
            void write(TestReport report);
        }

        private final OnHang onHang;
        private volatile @Nullable TestReport report;
        private volatile TestReport.@Nullable Suite suite;
        private volatile @Nullable String className, method, current, source;
        private volatile long deadline;
        private volatile long started;
        private volatile boolean running = true;
        private @Nullable Thread serverThread;

        Watchdog(OnHang onHang) {
            this.onHang = onHang;
        }

        void start(TestReport first) {
            report = first;
            serverThread = Thread.currentThread();
            Thread thread = new Thread(this::loop, "Hadron test watchdog");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
        }

        /**
         * A generous wall-clock limit: the machine may be slow, so it's far above what the ticks should take. A null
         * suite means a step outside any test (a warm server reloading a plugin), reported as a problem of the run.
         */
        void watch(TestReport report, TestReport.@Nullable Suite suite, @Nullable String className, @Nullable String method, String test,
                   @Nullable String source, int ticks) {
            this.report = report;
            this.suite = suite;
            this.className = className;
            this.method = method;
            this.source = source;
            started = System.currentTimeMillis();
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
                Thread thread = serverThread;
                TestReport hung = report;
                if (thread == null || hung == null) continue;
                StringBuilder where = new StringBuilder();
                int n = 0;
                for (StackTraceElement e : thread.getStackTrace()) {
                    if (n++ >= 30) break;
                    where.append("  at ").append(e).append('\n');
                }
                if (suite == null) {
                    hung.problem(test + " never finished, so the server was ended. The server thread was at:\n" + where);
                } else {
                    hung.add(suite, new TestReport.Result(test, className, method, TestReport.Status.HUNG, source,
                            null, test + " never finished: it ran past its wall-clock limit without returning, so the "
                            + "tick limit couldn't stop it. A loop that never calls t.tick() or t.await(), or a wait "
                            + "on another thread, does this. The tests after it didn't run. The server thread was at:",
                            where.toString(), 0, System.currentTimeMillis() - started));
                }
                onHang.write(hung);
                LOG.error("\n{}", hung.render());
                Runtime.getRuntime().halt(3);
            }
        }
    }
}
