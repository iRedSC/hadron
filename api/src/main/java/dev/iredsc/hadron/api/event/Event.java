package dev.iredsc.hadron.api.event;

/**
 * Something plugins can hear, fired by Hadron on whichever loader runs it. Extend it for a plugin's own events and
 * fire them with {@code getServer().getPluginManager().callEvent(event)}; listeners for a parent class hear every
 * subclass.
 */
public abstract class Event {
    public String getEventName() {
        return getClass().getSimpleName();
    }
}
