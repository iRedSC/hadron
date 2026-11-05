package dev.iredsc.hadron.core;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.Scheduler;
import dev.iredsc.hadron.api.plugin.Task;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The scheduler, driven by Hadron's end-of-tick hook. Safe to call from any thread. */
final class TaskScheduler implements Scheduler {
    private final PluginHost host;
    private final PriorityQueue<TaskImpl> queue = new PriorityQueue<TaskImpl>();
    private final Map<Integer, TaskImpl> byId = new HashMap<Integer, TaskImpl>();
    private final ExecutorService async = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Hadron async " + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });
    private long tick;
    private int nextId = 1;
    private volatile Thread serverThread;
    /** Async runs in progress per plugin version, so disabling can wait for them; guarded by itself. */
    private final Map<JavaPlugin, Integer> inFlight = new HashMap<JavaPlugin, Integer>();

    TaskScheduler(PluginHost host) {
        this.host = host;
    }

    void serverThread(Thread thread) {
        serverThread = thread;
    }

    final class TaskImpl implements Task, Comparable<TaskImpl>, Runnable {
        final int id;
        final JavaPlugin owner;
        final Runnable body;
        final boolean isAsync;
        final long period;
        long due;
        volatile boolean cancelled;

        TaskImpl(int id, JavaPlugin owner, Runnable body, boolean isAsync, long due, long period) {
            this.id = id;
            this.owner = owner;
            this.body = body;
            this.isAsync = isAsync;
            this.due = due;
            this.period = period;
        }

        @Override
        public int getTaskId() {
            return id;
        }

        @Override
        public JavaPlugin getOwner() {
            return owner;
        }

        @Override
        public boolean isAsync() {
            return isAsync;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelTask(id);
        }

        /** Runs unless cancelled or its plugin is no longer the running version; a one-shot then leaves the index. */
        @Override
        public void run() {
            if (isAsync) started(owner);
            try {
                if (cancelled || !host.isActive(owner)) return;
                body.run();
            } catch (Throwable t) {
                owner.getLogger().error("Task #" + id + " threw", t);
            } finally {
                if (period == 0) finished(this);
                if (isAsync) ended(owner);
            }
        }

        @Override
        public int compareTo(TaskImpl o) {
            return due != o.due ? Long.compare(due, o.due) : Integer.compare(id, o.id);
        }
    }

    private Task schedule(JavaPlugin plugin, Runnable task, long delay, long period, boolean isAsync) {
        if (task == null) throw new IllegalArgumentException("task is null");
        // Checked under the same lock cancelTasks takes, after the plugin is marked inactive, so a task can never be
        // added once its plugin's tasks have been cancelled.
        synchronized (this) {
            host.requireActive(plugin, "schedule tasks");
            TaskImpl t = new TaskImpl(nextId++, plugin, task, isAsync, tick + Math.max(1, delay),
                    period <= 0 ? 0 : period);
            queue.add(t);
            byId.put(t.id, t);
            return t;
        }
    }

    /** End of each server tick. */
    void tick() {
        List<TaskImpl> due = new ArrayList<TaskImpl>();
        synchronized (this) {
            tick++;
            while (!queue.isEmpty() && queue.peek().due <= tick) {
                TaskImpl t = queue.poll();
                if (t.cancelled) continue;
                due.add(t);
                // One-shots stay in byId until they've run, so cancelling them in the meantime still works.
                if (t.period > 0) {
                    t.due = tick + t.period;
                    queue.add(t);
                }
            }
        }
        for (TaskImpl t : due) {
            if (t.isAsync) {
                async.execute(t);
            } else {
                t.run();
            }
        }
    }

    private void started(JavaPlugin plugin) {
        synchronized (inFlight) {
            Integer n = inFlight.get(plugin);
            inFlight.put(plugin, n == null ? 1 : n + 1);
        }
    }

    private void ended(JavaPlugin plugin) {
        synchronized (inFlight) {
            Integer n = inFlight.get(plugin);
            if (n == null || n <= 1) inFlight.remove(plugin);
            else inFlight.put(plugin, n - 1);
            inFlight.notifyAll();
        }
    }

    /**
     * Waits up to {@code timeoutMillis} for the plugin's async runs already in progress to finish, so nothing of a
     * disabling version is still working while it saves, or after its next version starts. Called on the server
     * thread, where no new run can start meanwhile. @return how many were still running when it gave up; 0 when done.
     */
    int awaitAsync(JavaPlugin plugin, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (inFlight) {
            while (true) {
                Integer n = inFlight.get(plugin);
                if (n == null) return 0;
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) return n;
                try {
                    inFlight.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return n;
                }
            }
        }
    }

    private synchronized void finished(TaskImpl t) {
        if (byId.get(t.id) == t) byId.remove(t.id);
    }

    @Override
    public Task runTask(JavaPlugin plugin, Runnable task) {
        return schedule(plugin, task, 0, 0, false);
    }

    @Override
    public Task runTaskLater(JavaPlugin plugin, Runnable task, long delayTicks) {
        return schedule(plugin, task, delayTicks, 0, false);
    }

    @Override
    public Task runTaskTimer(JavaPlugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return schedule(plugin, task, delayTicks, Math.max(1, periodTicks), false);
    }

    @Override
    public Task runTaskAsynchronously(JavaPlugin plugin, Runnable task) {
        return schedule(plugin, task, 0, 0, true);
    }

    @Override
    public Task runTaskLaterAsynchronously(JavaPlugin plugin, Runnable task, long delayTicks) {
        return schedule(plugin, task, delayTicks, 0, true);
    }

    @Override
    public Task runTaskTimerAsynchronously(JavaPlugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return schedule(plugin, task, delayTicks, Math.max(1, periodTicks), true);
    }

    @Override
    public synchronized void cancelTask(int taskId) {
        TaskImpl t = byId.remove(taskId);
        if (t != null) {
            t.cancelled = true;
            queue.remove(t);
        }
    }

    @Override
    public synchronized void cancelTasks(JavaPlugin plugin) {
        for (TaskImpl t : new ArrayList<TaskImpl>(byId.values())) {
            if (t.owner == plugin) cancelTask(t.id);
        }
    }

    @Override
    public boolean isPrimaryThread() {
        return Thread.currentThread() == serverThread;
    }
}
