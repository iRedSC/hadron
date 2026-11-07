package dev.iredsc.hadron.selftest.plugin;

import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.api.plugin.Scheduler;
import dev.iredsc.hadron.api.plugin.Server;
import dev.iredsc.hadron.api.plugin.Task;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Tasks a plugin schedules: when they run, on which thread, and that a cancel stops them. */
public final class SchedulerTests {
    private static Scheduler scheduler(TestContext t) {
        return t.plugin().getServer().getScheduler();
    }

    @HadronTest
    @Covers(value = Server.class, method = "getScheduler")
    @Covers(value = Scheduler.class, method = "runTask")
    @Covers(value = Scheduler.class, method = "runTaskLater")
    @Covers(value = Scheduler.class, method = "isPrimaryThread")
    @Covers(value = Task.class, method = "getTaskId")
    @Covers(value = Task.class, method = "getOwner")
    @Covers(value = Task.class, method = "isAsync")
    void tasksRunOnTheServerThreadWhenTheirTickComes(TestContext t) {
        Scheduler scheduler = scheduler(t);
        List<String> ran = new CopyOnWriteArrayList<>();
        t.assertTrue(scheduler.isPrimaryThread(), "a test runs on the server thread");
        Task next = scheduler.runTask(t.plugin(), () -> ran.add("next tick, primary " + scheduler.isPrimaryThread()));
        Task later = scheduler.runTaskLater(t.plugin(), () -> ran.add("later"), 5);
        t.assertTrue(next.getTaskId() != later.getTaskId(), "each task has its own id");
        t.assertTrue(next.getOwner() == t.plugin(), "the task's owner is the plugin that scheduled it");
        t.assertFalse(next.isAsync(), "runTask is not async");
        t.assertEquals(List.of(), ran, "nothing runs before a tick");
        t.tick(2);
        t.assertEquals(List.of("next tick, primary true"), ran, "after two ticks");
        t.tick(5);
        t.assertEquals(List.of("next tick, primary true", "later"), ran, "after the delay");
    }

    @HadronTest
    @Covers(value = Scheduler.class, method = "runTaskTimer")
    @Covers(value = Scheduler.class, method = "cancelTask")
    @Covers(value = Task.class, method = "cancel")
    @Covers(value = Task.class, method = "isCancelled")
    void aTimerRepeatsUntilCancelled(TestContext t) {
        Scheduler scheduler = scheduler(t);
        AtomicInteger runs = new AtomicInteger();
        Task timer = scheduler.runTaskTimer(t.plugin(), runs::incrementAndGet, 1, 2);
        t.tick(9);
        int before = runs.get();
        t.assertTrue(before >= 3 && before <= 5, "a 2-tick timer should run about 4 times in 9 ticks, but ran "
                + before);
        t.assertFalse(timer.isCancelled(), "a running timer isn't cancelled");
        timer.cancel();
        t.assertTrue(timer.isCancelled(), "cancel should mark the task cancelled");
        t.tick(6);
        t.assertEquals(before, runs.get(), "runs after cancel");

        AtomicInteger other = new AtomicInteger();
        Task byId = scheduler.runTaskTimer(t.plugin(), other::incrementAndGet, 1, 1);
        scheduler.cancelTask(byId.getTaskId());
        t.tick(4);
        t.assertEquals(0, other.get(), "runs of a timer cancelled by id before its first run");
        t.assertTrue(byId.isCancelled(), "cancelTask should cancel the task with that id");
    }

    @HadronTest
    @Covers(value = Scheduler.class, method = "cancelTasks")
    void cancelTasksStopsEveryTaskOfThePlugin(TestContext t) {
        Scheduler scheduler = scheduler(t);
        AtomicInteger runs = new AtomicInteger();
        Task a = scheduler.runTaskLater(t.plugin(), runs::incrementAndGet, 3);
        Task b = scheduler.runTaskTimer(t.plugin(), runs::incrementAndGet, 3, 1);
        scheduler.cancelTasks(t.plugin());
        t.tick(6);
        t.assertEquals(0, runs.get(), "runs after cancelTasks");
        t.assertTrue(a.isCancelled() && b.isCancelled(), "both tasks should be cancelled");
    }

    @HadronTest
    @Covers(value = Scheduler.class, method = "runTaskAsynchronously")
    @Covers(value = Scheduler.class, method = "runTaskLaterAsynchronously")
    @Covers(value = Scheduler.class, method = "runTaskTimerAsynchronously")
    @Covers(value = Scheduler.class, method = "isPrimaryThread")
    @Covers(value = Task.class, method = "isAsync")
    @Covers(value = Task.class, method = "cancel")
    void asyncTasksRunOffTheServerThread(TestContext t) {
        Scheduler scheduler = scheduler(t);
        List<String> ran = new CopyOnWriteArrayList<>();
        Task now = scheduler.runTaskAsynchronously(t.plugin(), () -> ran.add("now primary=" + scheduler.isPrimaryThread()));
        Task later = scheduler.runTaskLaterAsynchronously(t.plugin(), () -> ran.add("later"), 2);
        AtomicInteger repeats = new AtomicInteger();
        Task timer = scheduler.runTaskTimerAsynchronously(t.plugin(), repeats::incrementAndGet, 1, 1);
        t.assertTrue(now.isAsync() && later.isAsync() && timer.isAsync(), "async tasks say they're async");
        t.await("the async tasks to run", () -> {
            sleep();
            return ran.size() == 2 && repeats.get() >= 2;
        }, 200);
        timer.cancel();
        t.assertTrue(ran.contains("now primary=false"), "an async task runs off the server thread, but ran " + ran);
        t.assertTrue(ran.contains("later"), "the delayed async task should have run");
    }

    @Nondeterministic(reason = "yields wall time to work on other threads (chunk workers, async tasks) between ticks, which run faster than it")
    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
