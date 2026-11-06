package dev.iredsc.hadron.api.net;

/** A registered packet handler. It is removed by itself when its plugin disables; call this to remove it sooner. */
public interface PacketRegistration {
    /** Stops the handler; packets already being handled may still reach it once. Safe to call twice. */
    void unregister();
}
