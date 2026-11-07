package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginManager;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.StoredUserList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Watch mode: a test server started with {@code -Dhadron.test.watch=<dir>} stays up and reruns a suite each time a
 * build stages it again, so iterating on a plugin doesn't pay a server boot each time. {@code ./gradlew hadronWatch}
 * starts it; {@code ./gradlew hadronRerun} stages a fresh build and waits for its report.
 *
 * <p>Everything goes through files in {@code <dir>}, so the build and the server never share more than a folder:
 * <ul>
 *   <li>{@code inbox/<token>/}: one staged build, its jar and {@code suite.properties} ({@code jar}, {@code tests}).
 *       The build writes it under a dot name and renames it, so the server never sees half of one. Builds land here
 *       whenever they like; the server takes them only between runs, so a reload never lands in a running test.</li>
 *   <li>{@code done/<token>.properties}: how that build's run went ({@code status=ran} with {@code result},
 *       {@code report} and {@code json}, or {@code status=superseded} when a newer build of the same suite came in
 *       first). The build waits for it.</li>
 *   <li>{@code reports/NNNN/report.txt} and {@code .json}, one pair per rerun, the last {@value #KEEP_REPORTS} kept;
 *       {@code report.txt} and {@code report.json} in {@code <dir>} are the latest.</li>
 *   <li>{@code watch.properties}: the server's {@code pid} and when it {@code started}, there while it runs.</li>
 *   <li>{@code last-run.properties}: the latest run's answer, kept for the life of the server.</li>
 *   <li>{@code stop}: asks the server to stop; the server exits 0 only then.</li>
 * </ul>
 *
 * <p>A rerun starts each suite as a cold run would: the old plugin is unloaded, its data folder deleted, the
 * overworld's shared state (game rules, time, weather, difficulty, ops, bans, whitelist, scoreboard) put back as it
 * was when the server started, and the new jar loaded by the host's own hot reload. Each test then gets its own world
 * and players as always.
 */
final class TestWatch {
    static final String WATCH_PROPERTY = "hadron.test.watch";
    /** How often, in ticks, the server looks in the inbox. */
    private static final int POLL_EVERY = 5;
    private static final int KEEP_REPORTS = 20;
    /** The host looks for jar changes every 20 ticks and loads a new one on its second look, so these are generous. */
    private static final int UNLOAD_TICKS = 100;
    private static final int LOAD_TICKS = 100;

    @StaticState(reason = "the warm test server's watcher: made when the server starts, ended when it stops")
    private static @Nullable TestWatch active;

    /** Waiting for a build; or taking one, from the moment it's queued until its report is written. */
    private enum State { WAITING, RUNNING }

    private record Request(String token, File dir, String jar, TestFilter filter) {}

    /** The rerun in progress, so a hang still writes its report and answers its build. */
    private record Current(Request request, TestReport report, File runDir, int run) {}

    private final MinecraftServer server;
    private final File dir;
    private final File inbox;
    private final File done;
    private final File reports;
    private final TestRunner runner;
    private final Shared shared;
    private State state = State.WAITING;
    /** Whether hadronWatchStop asked it to stop, the only stop that exits 0. */
    private volatile boolean stopAsked;
    private volatile @Nullable Current current;
    private int ticks;
    private int runs;

    private TestWatch(MinecraftServer server, File dir) {
        this.server = server;
        this.dir = dir;
        this.inbox = new File(dir, "inbox");
        this.done = new File(dir, "done");
        this.reports = new File(dir, "reports");
        this.runner = new TestRunner(server, this::hung);
        this.shared = new Shared(server);
    }

    static void register(File dir) {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            active = new TestWatch(e.getServer(), dir);
            active.start();
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (active != null) active.tick();
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            // 0 only when asked to stop: a crash or a test stopping the server must fail hadronWatch, not end it green.
            boolean asked = active != null && active.stopAsked;
            if (active != null) active.stopped();
            TestRunner.exitLater(asked ? 0 : 1);
        });
    }

    private void start() {
        inbox.mkdirs();
        done.mkdirs();
        reports.mkdirs();
        runner.open(new TestReport("watch", 0));
        Properties p = new Properties();
        p.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        p.setProperty("started", Long.toString(System.currentTimeMillis()));
        write(new File(dir, "watch.properties"), p);
        TestRunner.LOG.info("Hadron test server watching {}: each ./gradlew hadronRerun reruns that plugin's tests "
                + "here. ./gradlew hadronWatchStop (or Ctrl+C) stops it.", inbox);
    }

    private void stopped() {
        runner.watchdog.stop();
        new File(dir, "watch.properties").delete();
    }

    /** End of every server tick, including the ones a test runs itself; it acts only between runs. */
    private void tick() {
        if (state != State.WAITING || ++ticks % POLL_EVERY != 0) return;
        File stop = new File(dir, "stop");
        if (stop.exists()) {
            stop.delete();
            stopAsked = true;
            TestRunner.LOG.info("Stopping the Hadron test server, as asked");
            server.halt(false);
            return;
        }
        List<Request> requests = take();
        if (requests.isEmpty()) return;
        state = State.RUNNING;
        // Queued, so it runs from the loop between ticks, where a test may tick the server itself.
        server.tell(new TickTask(server.getTickCount(), () -> {
            try {
                for (Request r : requests) rerun(r);
            } finally {
                state = State.WAITING;
            }
        }));
    }

    /** The newest staged build of each suite; older ones of the same suite are answered as superseded. */
    private List<Request> take() {
        File[] dirs = inbox.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (dirs == null || dirs.length == 0) return List.of();
        Arrays.sort(dirs, Comparator.comparing(File::getName));
        Map<String, Request> newest = new LinkedHashMap<>();
        for (File d : dirs) {
            Properties p = read(new File(d, "suite.properties"));
            String jar = p.getProperty("jar");
            if (jar == null || !new File(d, jar).isFile()) {
                TestRunner.LOG.error("{} in the test inbox has no suite.properties naming its jar; ignored", d);
                deleteTree(d.toPath());
                continue;
            }
            Request r = new Request(d.getName(), d, jar, TestFilter.parse(p.getProperty("tests")));
            Request older = newest.put(jar, r);
            if (older != null) {
                Properties answer = new Properties();
                answer.setProperty("status", "superseded");
                answer.setProperty("supersededBy", r.token());
                write(new File(done, older.token() + ".properties"), answer);
                deleteTree(older.dir().toPath());
            }
        }
        return new ArrayList<>(newest.values());
    }

    private void rerun(Request r) {
        int n = ++runs;
        TestReport report = new TestReport("watch", n);
        File runDir = new File(reports, String.format("%04d", n));
        current = new Current(r, report, runDir, n);
        try {
            File jar = install(r, report);
            if (jar != null) runner.runSuite(report, jar, r.filter());
        } catch (Throwable t) {
            report.problem("The rerun itself failed, so its tests didn't all run: " + TestRunner.stack(t));
        } finally {
            finish(current);
            current = null;
            deleteTree(r.dir().toPath());
            prune();
        }
    }

    /**
     * Swaps the suite's plugin for the staged build, as a fresh server would start it: the old one unloaded, its data
     * deleted, the shared state put back, then the new jar loaded by the host. @return the jar in plugins/, or null
     * if the run can't go on (the report says why).
     */
    private @Nullable File install(Request r, TestReport report) throws IOException {
        File staged = new File(r.dir(), r.jar());
        String name = TestRunner.pluginName(staged);
        if (name == null) {
            report.broken(report.suite(r.jar(), "?"), "it has test classes but no plugin.yml, so no plugin to test; "
                    + "build it from a plugin project, whose resources hold plugin.yml");
            return null;
        }
        PluginManager plugins = Hadron.getPluginManager();
        File folder = Hadron.getServer().getPluginsFolder();
        File target = new File(folder, r.jar());
        JavaPlugin old = plugins.getPlugin(name);
        File data = old != null ? old.getDataFolder() : new File(folder, name);
        runner.watchdog.watch(report, null, null, null, "Reloading " + name, null, UNLOAD_TICKS + LOAD_TICKS);
        try {
            if (target.exists() || old != null) {
                Files.deleteIfExists(target.toPath());
                if (!tickUntil(() -> plugins.getPlugin(name) == null, UNLOAD_TICKS)) {
                    report.problem(name + " was still loaded " + UNLOAD_TICKS + " ticks after its old jar was removed, "
                            + "so its tests didn't rerun. Another jar in " + folder + " may also be " + name
                            + "; delete it, then rerun.");
                    return null;
                }
            }
            // What the old plugin saved as it disabled goes too, so the new one starts as on a fresh server.
            deleteTree(data.toPath());
            String problem = shared.restore();
            if (problem != null) report.problem(problem);
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            // If it never enables, the suite says so, with where to look.
            tickUntil(() -> plugins.isPluginEnabled(name), LOAD_TICKS);
            return target;
        } finally {
            runner.watchdog.unwatch();
        }
    }

    /** Runs server ticks back to back, without waiting out the clock, until {@code done} or {@code max} ticks. */
    private boolean tickUntil(BooleanSupplier done, int max) {
        for (int i = 0; i < max; i++) {
            if (done.getAsBoolean()) return true;
            server.tickServer(() -> false);
            int ran = 0;
            while (ran < 100_000 && server.pollTask()) ran++;
        }
        return done.getAsBoolean();
    }

    /** From the watchdog thread: the process ends right after, so this run's build hears it failed. */
    private void hung(TestReport report) {
        Current c = current;
        if (c != null && c.report() == report) finish(c);
        new File(dir, "watch.properties").delete();
    }

    /** Writes the run's report, as the latest too, and answers its build. */
    private synchronized void finish(Current c) {
        File text = new File(c.runDir(), "report.txt");
        c.report().write(text);
        File latest = new File(dir, "report.txt");
        try {
            Files.copy(text.toPath(), latest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(TestReport.jsonFile(text).toPath(), TestReport.jsonFile(latest).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            TestRunner.LOG.error("Couldn't copy the report to {}", latest, e);
        }
        Properties answer = new Properties();
        answer.setProperty("status", "ran");
        answer.setProperty("run", Integer.toString(c.run()));
        answer.setProperty("result", c.report().passed() ? "PASSED" : "FAILED");
        answer.setProperty("report", text.getAbsolutePath());
        answer.setProperty("json", TestReport.jsonFile(text).getAbsolutePath());
        write(new File(done, c.request().token() + ".properties"), answer);
        // Never pruned: hadronWatch reads it as proof the server finished a run.
        write(new File(dir, "last-run.properties"), answer);
        TestRunner.LOG.info("\n{}\nRerun {} report: {} and {}", c.report().render(), c.run(), text,
                TestReport.jsonFile(text));
    }

    private void prune() {
        File[] runDirs = reports.listFiles(File::isDirectory);
        if (runDirs == null || runDirs.length <= KEEP_REPORTS) return;
        Arrays.sort(runDirs, Comparator.comparing(File::getName));
        for (int i = 0; i < runDirs.length - KEEP_REPORTS; i++) deleteTree(runDirs[i].toPath());
        // Old answers too: a build reads its own within seconds.
        File[] answers = done.listFiles();
        if (answers == null) return;
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        for (File a : answers) if (a.lastModified() < cutoff) a.delete();
    }

    /**
     * The overworld state every test shares, as it was when the server started, so a rerun starts from what a cold
     * run would: a test that changed the time, a game rule or the scoreboard and didn't put it back can't make the
     * next rerun pass or fail.
     */
    private static final class Shared {
        private final MinecraftServer server;
        private final GameRules rules;
        private final long dayTime;
        private final int clearTime, rainTime;
        private final boolean raining, thundering;
        private final Difficulty difficulty;

        Shared(MinecraftServer server) {
            this.server = server;
            this.rules = server.getGameRules().copy();
            this.dayTime = server.overworld().getDayTime();
            ServerLevelData data = server.getWorldData().overworldData();
            this.clearTime = data.getClearWeatherTime();
            this.rainTime = data.getRainTime();
            this.raining = data.isRaining();
            this.thundering = data.isThundering();
            this.difficulty = server.getWorldData().getDifficulty();
        }

        /** @return what couldn't be put back, or null. */
        @Nullable String restore() {
            List<String> failed = new ArrayList<>();
            step(failed, "game rules", () -> server.getGameRules().assignFrom(rules, server));
            step(failed, "time and weather", () -> {
                server.overworld().setDayTime(dayTime);
                server.overworld().setWeatherParameters(clearTime, rainTime, raining, thundering);
            });
            step(failed, "difficulty", () -> server.setDifficulty(difficulty, true));
            // A fresh test server has no ops, bans or whitelist; tests' players never join one with any.
            PlayerList players = server.getPlayerList();
            step(failed, "ops", () -> clear(players.getOps()));
            step(failed, "bans", () -> {
                clear(players.getBans());
                clear(players.getIpBans());
            });
            step(failed, "whitelist", () -> {
                clear(players.getWhiteList());
                players.setUsingWhiteList(false);
            });
            step(failed, "scoreboard", () -> {
                Scoreboard board = server.getScoreboard();
                for (Objective o : new ArrayList<>(board.getObjectives())) board.removeObjective(o);
                for (PlayerTeam t : new ArrayList<>(board.getPlayerTeams())) board.removePlayerTeam(t);
            });
            return failed.isEmpty() ? null : "Putting back the server's shared state before this rerun failed, so its "
                    + "tests may see an earlier run's leftovers: " + String.join("; ", failed);
        }

        private static <K, V extends net.minecraft.server.players.StoredUserEntry<K>> void clear(StoredUserList<K, V> list) {
            for (V entry : new ArrayList<>(list.getEntries())) list.remove(entry);
        }

        private static void step(List<String> failed, String what, Runnable step) {
            try {
                step.run();
            } catch (RuntimeException e) {
                failed.add(what + ": " + e);
            }
        }
    }

    private static Properties read(File file) {
        Properties p = new Properties();
        if (!file.isFile()) return p;
        try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            TestRunner.LOG.error("Couldn't read {}", file, e);
        }
        return p;
    }

    /** Written under a temporary name and renamed, so a reader never sees half of it. */
    private static void write(File file, Properties p) {
        file.getParentFile().mkdirs();
        File part = new File(file.getParentFile(), "." + file.getName() + ".part");
        try {
            try (Writer out = Files.newBufferedWriter(part.toPath(), StandardCharsets.UTF_8)) {
                p.store(out, null);
            }
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            TestRunner.LOG.error("Couldn't write {}", file, e);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            TestRunner.LOG.error("Couldn't delete {}", root, e);
        }
    }
}
