package dev.iredsc.hadron.api.plugin;

/** A plugin that can't load or enable, with a message saying why in a line an admin can act on. */
public class PluginException extends RuntimeException {
    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
