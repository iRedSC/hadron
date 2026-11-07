package dev.iredsc.hadron.api.plugin;

import java.io.File;
import java.io.InputStream;
import org.slf4j.Logger;

/**
 * What a {@link JavaPlugin} learns about itself from the class loader Hadron loads it with. Only Hadron implements
 * this, so a plugin class constructed any other way fails at once instead of half working.
 */
public interface PluginContext {
    PluginDescription description();

    /** The jar as it sits in {@code plugins/}. */
    File file();

    /** {@code plugins/<name>/}. */
    File dataFolder();

    Logger logger();

    Server server();

    /** Called once the plugin object exists, before {@link JavaPlugin#onLoad}. */
    void attach(JavaPlugin plugin);

    boolean enabled();

    /** Loaded or enabled, and not yet unloading. Once false it never turns true again. */
    boolean alive();

    /** A command from plugin.yml; null for a name it doesn't declare. */
    PluginCommand command(String name);

    /** A file inside the plugin's own jar (never another mod's or plugin's), or null. */
    InputStream resource(String path);
}
