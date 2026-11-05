package dev.iredsc.hadron.examples.hello;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;

/** Fired by /hello before it answers, so other code can stop the greeting: a plugin's own event. */
public class HelloEvent extends Event implements Cancellable {
    private final String who;
    private boolean cancelled;

    public HelloEvent(String who) {
        this.who = who;
    }

    public String getWho() {
        return who;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }
}
