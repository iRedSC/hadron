package dev.iredsc.hadron.api.plugin;

import dev.iredsc.hadron.api.config.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;

/**
 * A plugin's main class, named by {@code main} in its plugin.yml, as in Bukkit. Hadron loads each plugin jar from
 * {@code plugins/} with its own class loader, so a changed jar reloads while the server runs: the old version is
 * disabled, everything it registered through Hadron is undone, and the new one is enabled.
 *
 * <p>Register through {@link #getServer()} (events, commands, tasks, services), never directly on NeoForge's buses:
 * those would outlive a reload.
 */
public abstract class JavaPlugin implements CommandExecutor, TabCompleter {
    private final PluginContext context;
    private YamlConfiguration config;

    protected JavaPlugin() {
        if (!(getClass().getClassLoader() instanceof PluginContext loader)) {
            throw new IllegalStateException(getClass().getName() + " is a plugin; only Hadron may create it, from a "
                    + "jar in plugins/");
        }
        context = loader;
        context.attach(this);
    }

    /**
     * Before any plugin enables: at server start before worlds load, and again on every reload. Register services
     * here, so every plugin can use them in onEnable whatever order plugins load in. Listeners, commands and tasks
     * wait for onEnable.
     */
    public void onLoad() {}

    /** Register listeners, commands and tasks here. Throwing leaves the plugin disabled and logs why. */
    public void onEnable() {}

    /** Save here. It runs on every reload, not just at shutdown. Registrations are undone for you afterwards. */
    public void onDisable() {}

    /** For commands in plugin.yml without their own executor. @return false to show usage. */
    @Override
    public boolean onCommand(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSourceStack sender, PluginCommand command, String label, String[] args) {
        return null;
    }

    public final String getName() {
        return context.description().getName();
    }

    public final PluginDescription getDescription() {
        return context.description();
    }

    public final Logger getLogger() {
        return context.logger();
    }

    public final Server getServer() {
        return context.server();
    }

    /** {@code plugins/<name>/}, made on first use of {@link #saveDefaultConfig} or {@link #saveResource}. */
    public final File getDataFolder() {
        return context.dataFolder();
    }

    /** The plugin's jar in {@code plugins/}. */
    public final File getFile() {
        return context.file();
    }

    public final boolean isEnabled() {
        return context.enabled();
    }

    /** A command from plugin.yml. @throws IllegalArgumentException for one plugin.yml doesn't declare. */
    public final PluginCommand getCommand(String name) {
        PluginCommand command = context.command(name);
        if (command == null) {
            throw new IllegalArgumentException(getName() + "'s plugin.yml has no command '" + name + "'. Add it under "
                    + "commands:, or register a Brigadier command with getServer().getPluginManager().registerCommand");
        }
        return command;
    }

    /** A file from inside this plugin's jar, or null. */
    public final InputStream getResource(String path) {
        return context.resource(path);
    }

    /** {@code config.yml} in the data folder, with the jar's own config.yml behind it for anything missing. */
    public final YamlConfiguration getConfig() {
        if (config == null) reloadConfig();
        return config;
    }

    public final void reloadConfig() {
        config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        try (InputStream defaults = getResource("config.yml")) {
            if (defaults != null) {
                config.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // Closing a read-only stream from our own jar.
        }
    }

    public final void saveConfig() {
        try {
            getConfig().save(new File(getDataFolder(), "config.yml"));
        } catch (IOException e) {
            getLogger().error("Couldn't save config.yml", e);
        }
    }

    /** Writes the jar's config.yml into the data folder, unless one is there already. */
    public final void saveDefaultConfig() {
        if (!new File(getDataFolder(), "config.yml").exists()) saveResource("config.yml", false);
    }

    /** Copies a file from the jar into the data folder, at the same relative path. */
    public final void saveResource(String path, boolean replace) {
        File out = new File(getDataFolder(), path);
        try (InputStream in = getResource(path)) {
            if (in == null) throw new IllegalArgumentException(getName() + "'s jar has no " + path);
            if (out.exists() && !replace) return;
            Files.createDirectories(out.getParentFile().toPath());
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().error("Couldn't save " + out, e);
        }
    }

    @Override
    public final String toString() {
        return getDescription().getFullName();
    }
}
