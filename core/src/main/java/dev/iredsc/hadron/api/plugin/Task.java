package dev.iredsc.hadron.api.plugin;

/** A scheduled task. Cancelled for you when its plugin disables. */
public interface Task {
    int getTaskId();

    JavaPlugin getOwner();

    /** Whether it runs off the server thread. */
    boolean isAsync();

    boolean isCancelled();

    /** Stops future runs. A run already in progress finishes. */
    void cancel();
}
