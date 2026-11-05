package dev.iredsc.hadron.api.config;

/** YAML that doesn't parse, or isn't keys and values at the top. */
public class InvalidConfigurationException extends Exception {
    public InvalidConfigurationException(String message) {
        super(message);
    }

    public InvalidConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
