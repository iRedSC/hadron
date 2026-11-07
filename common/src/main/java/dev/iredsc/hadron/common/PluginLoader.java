package dev.iredsc.hadron.common;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import dev.iredsc.hadron.api.plugin.PluginContext;
import dev.iredsc.hadron.api.plugin.PluginDescription;
import dev.iredsc.hadron.api.plugin.Server;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One loaded version of one plugin jar. Classes come from Minecraft, NeoForge and the mods first (so every plugin
 * shares one Minecraft), then the plugin's own jar, then the plugins it declares in depend and softdepend. A plugin
 * can't see a plugin it didn't declare, which is what lets one reload without stranding another on its old classes.
 *
 * <p>Reads a private copy of the jar, so replacing the file in {@code plugins/} mid-load can't corrupt classes this
 * version still has to read while it disables.
 */
final class PluginLoader extends URLClassLoader implements PluginContext {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final PluginDescription description;
    private final File original;
    private final File copy;
    private final File dataFolder;
    private final Logger logger;
    private final Server server;
    private final List<PluginLoader> dependencies;
    private final Map<String, PluginCommand> commands = new LinkedHashMap<>();
    private JavaPlugin plugin;
    private enum Phase { LOADED, ENABLED, UNLOADING, DISPOSED }
    private volatile Phase phase = Phase.LOADED;

    PluginLoader(PluginDescription description, File original, File copy, File dataFolder, Server server,
            List<PluginLoader> dependencies, ClassLoader parent) throws MalformedURLException {
        super("plugin " + description.getName(), new URL[] {copy.toURI().toURL()}, parent);
        this.description = description;
        this.original = original;
        this.copy = copy;
        this.dataFolder = dataFolder;
        this.server = server;
        this.dependencies = dependencies;
        this.logger = LoggerFactory.getLogger(description.getName());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = getParent().loadClass(name);
                } catch (ClassNotFoundException ignored) {
                    // Not Minecraft's, NeoForge's or a mod's: ours or a dependency's.
                }
            }
            if (c == null) c = findOwnOrDependency(name);
            if (c == null) {
                throw new ClassNotFoundException(name + " isn't in " + description.getName() + " or the plugins it "
                        + "lists under depend or softdepend");
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    /** From this jar, then from the declared plugins' jars and theirs. Null when none has it. */
    private Class<?> findOwnOrDependency(String name) {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;
            try {
                return findClass(name);
            } catch (ClassNotFoundException ignored) {
                // Not in this jar.
            }
        }
        for (PluginLoader dependency : dependencies) {
            Class<?> c = dependency.findOwnOrDependency(name);
            if (c != null) return c;
        }
        return null;
    }

    @Override
    public PluginDescription description() {
        return description;
    }

    @Override
    public File file() {
        return original;
    }

    @Override
    public File dataFolder() {
        return dataFolder;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Server server() {
        return server;
    }

    @Override
    public void attach(JavaPlugin plugin) {
        if (this.plugin != null) {
            throw new IllegalStateException(description.getName() + " already has its main class, "
                    + this.plugin.getClass().getName() + "; a jar holds one JavaPlugin");
        }
        this.plugin = plugin;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    boolean belongsTo(Server server) {
        return this.server == server;
    }

    @Override
    public boolean alive() {
        return phase == Phase.LOADED || phase == Phase.ENABLED;
    }

    boolean hasUnloadedDependencies() {
        return dependencies.stream().anyMatch(dependency -> !dependency.alive());
    }

    void enable() {
        if (phase != Phase.LOADED) throw new IllegalStateException("Can't enable " + this + " from " + phase);
        phase = Phase.ENABLED;
    }

    void beginUnload() {
        if (phase != Phase.DISPOSED) phase = Phase.UNLOADING;
    }

    @Override
    public boolean enabled() {
        return phase == Phase.ENABLED;
    }

    @Override
    public PluginCommand command(String name) {
        return commands.get(name.toLowerCase(Locale.ROOT));
    }

    Map<String, PluginCommand> commands() {
        return commands;
    }

    @Override
    public InputStream resource(String path) {
        URL url = findResource(path);
        if (url == null) return null;
        try {
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }

    /** Releases the jar copy. Classes already loaded keep working for whoever still holds them. */
    void dispose() {
        phase = Phase.DISPOSED;
        try {
            close();
        } catch (IOException ignored) {
            // Closing a read-only jar.
        }
        if (!copy.delete()) copy.deleteOnExit();
    }

    @Override
    public String toString() {
        return "PluginLoader[" + description.getFullName() + "]";
    }
}
