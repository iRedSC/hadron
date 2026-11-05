package dev.iredsc.hadron.api.plugin;

/**
 * Runs code later, like Bukkit's scheduler. Delays and periods are in server ticks (20 a second). Sync tasks run on
 * the server thread at the end of a tick, where game state may be read and changed freely; async tasks run on a
 * worker thread and must not touch the world. Every task is cancelled when its plugin disables, so a reloaded plugin
 * never runs its old code. Async runs already in progress are waited for (up to 5 seconds) before onDisable.
 */
public interface Scheduler {
    /** On the next tick. Use it in onEnable to act once every plugin has enabled and the server is running. */
    Task runTask(JavaPlugin plugin, Runnable task);

    Task runTaskLater(JavaPlugin plugin, Runnable task, long delayTicks);

    Task runTaskTimer(JavaPlugin plugin, Runnable task, long delayTicks, long periodTicks);

    Task runTaskAsynchronously(JavaPlugin plugin, Runnable task);

    Task runTaskLaterAsynchronously(JavaPlugin plugin, Runnable task, long delayTicks);

    Task runTaskTimerAsynchronously(JavaPlugin plugin, Runnable task, long delayTicks, long periodTicks);

    void cancelTask(int taskId);

    void cancelTasks(JavaPlugin plugin);

    /** Whether the calling thread is the server thread. */
    boolean isPrimaryThread();
}
