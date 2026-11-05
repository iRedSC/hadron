package dev.iredsc.hadron.api.event;

/** An event a listener can stop. Whoever fired it checks {@link #isCancelled()} afterwards and doesn't go ahead. */
public interface Cancellable {
    boolean isCancelled();

    void setCancelled(boolean cancel);
}
