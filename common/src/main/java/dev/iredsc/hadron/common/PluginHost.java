package dev.iredsc.hadron.common;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.function.Supplier;
import dev.iredsc.hadron.api.Default;
import dev.iredsc.hadron.api.Node;
import dev.iredsc.hadron.api.Permissions;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.server.PluginDisableEvent;
import dev.iredsc.hadron.api.event.server.PluginEnableEvent;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.PluginCommand;
import dev.iredsc.hadron.api.plugin.PluginDescription;
import dev.iredsc.hadron.api.plugin.PluginException;
import dev.iredsc.hadron.api.plugin.PluginManager;
import dev.iredsc.hadron.api.plugin.Server;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads every jar in {@code plugins/} as a plugin, enables them in dependency order, and swaps one out while the
 * server runs when its jar changes: it and everything that depends on it are disabled, everything they registered is
 * undone, and the new versions are enabled. A broken plugin fails alone and says why; the rest keep running.
 * <p>
 * Everything here runs on the server thread except {@link #requireActive}, which the scheduler and services call from
 * any thread.
 */
public final class PluginHost implements PluginManager {
    private static final Logger LOG = LoggerFactory.getLogger("Hadron");
    /** Whoever may manage plugins also hears when one reloads or fails. */
    public static final Node MANAGE = Node.declare("hadron.manage", Default.OPS,
            "Reload, enable and disable plugins, and hear when one reloads or fails");
    public static final Node LIST = Node.declare("hadron.plugins", Default.EVERYONE, "See /plugins");
    private static final int POLL_TICKS = 20;
    /** How long disabling waits for a plugin's async runs in progress; the server thread stalls at most this long. */
    private static final long ASYNC_GRACE_MILLIS = 5000;

    /** What one plugin is doing. Only the host moves an entry from one state to another. */
    abstract static class State {
        /** Has a loader and code; holds what to undo when it stops, newest first. */
        abstract static class Running extends State {
            final PluginLoader loader;
            final Deque<Runnable> undo;
            final List<EventBus.Subscription> subscriptions;

            Running(PluginLoader loader, Deque<Runnable> undo, List<EventBus.Subscription> subscriptions) {
                this.loader = loader;
                this.undo = undo;
                this.subscriptions = subscriptions;
            }
        }

        /**
         * Constructed and {@code onLoad} done, waiting for the rest of its batch to load before it enables, so every
         * plugin's placeholders exist before any plugin's configs are read.
         */
        static final class Loaded extends Running {
            Loaded(PluginLoader loader) {
                super(loader, new ArrayDeque<Runnable>(), new ArrayList<EventBus.Subscription>());
            }
        }

        /** Running: {@code onEnable} has been called. Keeps the undo list it was loaded with. */
        static final class Enabled extends Running {
            Enabled(Loaded loaded) {
                super(loaded.loader, loaded.undo, loaded.subscriptions);
            }
        }

        static final class Failed extends State {
            final String why;
            Failed(String why) {
                this.why = why;
            }
        }

        /** Switched off with /plugin disable. Stays off across reloads and restarts until /plugin enable. */
        static final class Off extends State {
        }

        /** Not started, or stopped with the server. */
        static final class Stopped extends State {
        }
    }

    /** One plugin, by its plugin.yml name, across every version of its jar. */
    static final class Entry {
        final String name;
        File jar;
        Stamp stamp;
        PluginDescription description;
        State state = new State.Stopped();

        Entry(String name, File jar, Stamp stamp, PluginDescription description) {
            this.name = name;
            this.jar = jar;
            this.stamp = stamp;
            this.description = description;
        }

        Set<String> needs() {
            Set<String> out = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            if (description != null) {
                out.addAll(description.getDepend());
                out.addAll(description.getSoftDepend());
            }
            return out;
        }
    }

    /** A jar's modification time and size; equal stamps mean an unchanged file. */
    static final class Stamp {
        final long modified;
        final long size;

        Stamp(File file) {
            modified = file.lastModified();
            size = file.length();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Stamp && ((Stamp) o).modified == modified && ((Stamp) o).size == size;
        }

        @Override
        public int hashCode() {
            return (int) (modified * 31 + size);
        }
    }

    private final File folder;
    private final File loadedDir;
    private final File disabledFile;
    private final ClassLoader parent;
    private final MinecraftServer minecraft;
    private final ServerImpl server;
    private final TaskScheduler scheduler;
    private final Services services;
    private final dev.iredsc.hadron.common.economy.EconomyCore economy;
    private final Platform platform;
    private final EventBus events = new EventBus();
    private final Commands commands;
    private final Map<String, Entry> entries = new TreeMap<String, Entry>(String.CASE_INSENSITIVE_ORDER);
    private final List<Entry> enabledOrder = new ArrayList<Entry>();
    private final Set<String> off = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    /** Jars seen changed once; acted on when the next poll finds them the same, so a half-copied jar waits. */
    private final Map<File, Stamp> settling = new HashMap<File, Stamp>();
    /** Jars that can't be used as they are (duplicate name, unreadable), by the stamp they were refused at. */
    private final Map<File, Stamp> refused = new HashMap<File, Stamp>();
    private int copies;
    private int ticks;
    private boolean running;
    /** The plugin whose onLoad is running: the only time a plugin may register placeholders. */
    private volatile PluginLoader loading;
    private volatile boolean stopping;
    private boolean managing;
    private final Deque<Runnable> deferred = new ArrayDeque<Runnable>();
    /** Loaded at pre-initialization, enabled at server start, in this order. */
    private List<Entry> startupOrder = Collections.emptyList();

    public PluginHost(MinecraftServer minecraft, File serverDir, Platform platform) {
        this.platform = platform;
        File configFolder = platform.configFolder();
        this.minecraft = minecraft;
        this.folder = new File(serverDir, "plugins");
        this.loadedDir = new File(folder, ".loaded");
        this.disabledFile = new File(folder, "disabled.txt");
        this.parent = PluginHost.class.getClassLoader();
        this.server = new ServerImpl(this, minecraft, folder, configFolder);
        this.scheduler = new TaskScheduler(this);
        this.services = new Services(this);
        this.commands = new Commands(minecraft);
        this.economy = new dev.iredsc.hadron.common.economy.EconomyCore(this, minecraft, new File(serverDir, "hadron"));
        readOff();
    }

    public Server server() {
        return server;
    }

    boolean stopping() {
        return stopping;
    }

    TaskScheduler scheduler() {
        return scheduler;
    }

    Services services() {
        return services;
    }

    /** For the loader port: adds every live plugin command, and Hadron's own, to a new dispatcher. */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<PluginHost> hosts) {
        HadronCommand.register(dispatcher, hosts);
        PluginHost current = hosts.get();
        if (current != null) current.commands().apply(dispatcher);
    }

    Commands commands() {
        return commands;
    }

    // ---- Lifecycle, driven by Hadron ----

    /** Before worlds load, as in Bukkit: reads every jar, constructs each plugin and calls its onLoad. */
    public void load() {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            LOG.error("Can't create " + folder.getAbsolutePath() + ", so no plugins will load");
        }
        deleteChildren(loadedDir);
        economy.start();
        for (Map.Entry<File, Stamp> jar : scan().entrySet()) adopt(jar.getKey(), jar.getValue());
        manage(new Runnable() {
            @Override
            public void run() {
                startupOrder = loadInOrder(new ArrayList<Entry>(entries.values()));
            }
        });
    }

    /** At server start, once worlds exist and commands can be added. */
    public void start() {
        scheduler.serverThread(Thread.currentThread());
        running = true;
        manage(new Runnable() {
            @Override
            public void run() {
                for (Entry e : startupOrder) if (e.state instanceof State.Loaded) start(e);
                startupOrder = Collections.emptyList();
            }
        });
        int enabled = enabledOrder.size();
        LOG.info("Plugins: " + enabled + " of " + entries.size() + " enabled from " + folder.getAbsolutePath()
                + (enabled < entries.size() ? "; /plugins shows why the rest aren't" : ""));
    }

    /** End of every server tick. */
    public void tick() {
        scheduler.tick();
        if (!running || ++ticks % POLL_TICKS != 0) return;
        manage(new Runnable() {
            @Override
            public void run() {
                poll();
            }
        });
    }

    /**
     * Runs one management step (poll, reload, enable, disable) at a time. A step asked for while another runs (a
     * plugin dispatching /plugin from its own onEnable or onDisable, say) waits until that one finishes, so no
     * entry is ever changed halfway through its own transition.
     */
    private void manage(Runnable step) {
        if (managing) {
            deferred.add(step);
            return;
        }
        managing = true;
        try {
            for (Runnable next = step; next != null; next = deferred.poll()) {
                try {
                    next.run();
                } catch (RuntimeException e) {
                    LOG.error("Managing plugins failed", e);
                }
            }
        } finally {
            managing = false;
        }
    }

    /** At server stop, before anything saves: every plugin disables (and saves) in reverse order. */
    public void stop() {
        running = false;
        stopping = true;
        manage(new Runnable() {
            @Override
            public void run() {
                List<Entry> order = new ArrayList<Entry>(enabledOrder);
                Collections.reverse(order);
                for (Entry e : order) disable(e, new State.Stopped());
                for (Entry e : entries.values()) disable(e, new State.Stopped());
                startupOrder = Collections.emptyList();
                scheduler.close();
            }
        });
        economy.close();
        deleteChildren(loadedDir);
    }

    // ---- Finding jars ----

    private Map<File, Stamp> scan() {
        Map<File, Stamp> out = new TreeMap<File, Stamp>();
        File[] files = folder.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".jar")) out.put(f, new Stamp(f));
        }
        return out;
    }

    /** Reads a jar into a new entry. @return the entry, or null if the jar was refused (and logged). */
    private Entry adopt(File jar, Stamp stamp) {
        PluginDescription description;
        try {
            description = describe(jar);
        } catch (PluginException e) {
            refuse(jar, stamp, jar.getName() + " isn't a usable plugin: " + e.getMessage());
            return null;
        }
        Entry existing = entries.get(description.getName());
        if (existing != null && !existing.jar.equals(jar)) {
            refuse(jar, stamp, jar.getName() + " is ignored: " + existing.jar.getName() + " is already "
                    + description.getName() + ". Delete one of them.");
            return null;
        }
        refused.remove(jar);
        Entry e = new Entry(description.getName(), jar, stamp, description);
        entries.put(e.name, e);
        return e;
    }

    private void refuse(File jar, Stamp stamp, String why) {
        refused.put(jar, stamp);
        LOG.error(why);
        announce(red(why));
    }

    private static PluginDescription describe(File jar) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(jar);
            ZipEntry yml = zip.getEntry("plugin.yml");
            if (yml == null) throw new PluginException("it has no plugin.yml");
            InputStream in = zip.getInputStream(yml);
            Object parsed;
            try {
                parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new PluginException("its plugin.yml isn't valid YAML: " + e.getMessage());
            }
            if (!(parsed instanceof Map)) throw new PluginException("its plugin.yml isn't keys and values");
            return PluginDescription.parse((Map<?, ?>) parsed);
        } catch (IOException e) {
            throw new PluginException("it can't be read as a jar (" + e.getMessage() + ")");
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // Read only.
                }
            }
        }
    }

    // ---- Watching for changes ----

    private void poll() {
        Map<File, Stamp> now = scan();
        // A new jar with a running plugin's name replaces it in place (a deploy renames the file, version or not),
        // so the old version keeps running until the new one is ready instead of its dependents losing it.
        Map<String, File> newByName = new TreeMap<String, File>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<File, Stamp> jar : now.entrySet()) {
            if (entryFor(jar.getKey()) != null || jar.getValue().equals(refused.get(jar.getKey()))) continue;
            try {
                newByName.put(describe(jar.getKey()).getName(), jar.getKey());
            } catch (PluginException notYet) {
                // Half copied, or broken: the settle step below deals with it.
            }
        }
        for (Entry e : new ArrayList<Entry>(entries.values())) {
            if (now.containsKey(e.jar)) continue;
            File replacement = newByName.get(e.name);
            if (replacement != null) {
                e.jar = replacement;
                e.stamp = null;
            } else {
                // Jars that went away take their plugin with them.
                remove(e);
            }
        }
        for (Map.Entry<File, Stamp> jar : now.entrySet()) {
            File file = jar.getKey();
            Stamp stamp = jar.getValue();
            Entry e = entryFor(file);
            if (e != null && stamp.equals(e.stamp)) {
                settling.remove(file);
                continue;
            }
            if (e == null && stamp.equals(refused.get(file))) continue;
            if (!stamp.equals(settling.get(file))) {
                settling.put(file, stamp);
                continue;
            }
            settling.remove(file);
            if (e == null) {
                added(file, stamp);
            } else {
                changed(e, file, stamp);
            }
        }
    }

    private Entry entryFor(File jar) {
        for (Entry e : entries.values()) if (e.jar.equals(jar)) return e;
        return null;
    }

    private void added(File jar, Stamp stamp) {
        Entry e = adopt(jar, stamp);
        if (e == null) return;
        // Plugins that soft-depend on it reload too, so they can start using it.
        Set<Entry> roots = new LinkedHashSet<Entry>();
        roots.add(e);
        for (Entry other : entries.values()) if (other.needs().contains(e.name)) roots.add(other);
        restart(roots, "added");
    }

    private void changed(Entry e, File jar, Stamp stamp) {
        PluginDescription description;
        try {
            description = describe(jar);
        } catch (PluginException ex) {
            // The old version keeps running until the jar is fixed.
            refuse(jar, stamp, jar.getName() + " changed but isn't usable, so the running version stays: "
                    + ex.getMessage());
            e.stamp = stamp;
            return;
        }
        if (!description.getName().equalsIgnoreCase(e.name)) {
            remove(e);
            added(jar, stamp);
            return;
        }
        e.stamp = stamp;
        restart(Collections.singleton(e), "reloaded");
    }

    private void remove(Entry e) {
        Set<Entry> dependents = dependents(Collections.singleton(e));
        dependents.remove(e);
        disableAll(dependents);
        disable(e, new State.Stopped());
        entries.remove(e.name);
        announce(gray(e.name + " removed"));
        if (!dependents.isEmpty()) enableInOrder(new ArrayList<Entry>(dependents));
    }

    // ---- Enabling and disabling ----

    /**
     * Disables the roots and everything that depends on them, rereads their jars, and enables them all again in
     * dependency order. Dependents restart too because they may hold the roots' classes.
     */
    private void restart(Collection<Entry> roots, String verb) {
        if (stopping) return;
        Set<Entry> all = dependents(roots);
        disableAll(all);
        List<Entry> usable = new ArrayList<Entry>();
        for (Entry e : all) {
            try {
                PluginDescription d = describe(e.jar);
                if (!d.getName().equalsIgnoreCase(e.name)) {
                    throw new PluginException("its plugin.yml now names it " + d.getName());
                }
                e.description = d;
                usable.add(e);
            } catch (PluginException ex) {
                fail(e, ex.getMessage(), null);
            }
        }
        long start = System.nanoTime();
        enableInOrder(usable);
        long ms = (System.nanoTime() - start) / 1000000;
        for (Entry e : roots) {
            if (e.state instanceof State.Enabled) {
                announce(green(e.description.getFullName() + " " + verb + " (" + ms + " ms)"));
            }
        }
    }

    /** The entries and every entry that depends on them, directly or through others. */
    private Set<Entry> dependents(Collection<Entry> roots) {
        Set<Entry> out = new LinkedHashSet<Entry>(roots);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Entry e : entries.values()) {
                if (out.contains(e)) continue;
                for (Entry in : new ArrayList<Entry>(out)) {
                    if (e.needs().contains(in.name)) {
                        out.add(e);
                        grew = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Disables them, dependents before what they depend on. */
    private void disableAll(Collection<Entry> which) {
        List<Entry> order = new ArrayList<Entry>(enabledOrder);
        Collections.reverse(order);
        for (Entry e : order) if (which.contains(e)) disable(e, new State.Stopped());
        List<Entry> remaining = new ArrayList<>(which);
        Collections.reverse(remaining);
        for (Entry e : remaining) if (e.state instanceof State.Loaded) disable(e, new State.Stopped());
    }

    /**
     * Loads all of them, then enables all of them, each after what it needs. Loading first means every plugin's
     * {@code onLoad} (where placeholders are registered) runs before any {@code onEnable} reads a config that uses
     * them, whatever order the plugins happen to be in.
     */
    private void enableInOrder(List<Entry> which) {
        if (stopping) return;
        for (Entry e : loadInOrder(which)) if (e.state instanceof State.Loaded) start(e);
    }

    /** Loads each after what it needs. @return them in that order. A dependency cycle fails every plugin in it. */
    private List<Entry> loadInOrder(List<Entry> which) {
        List<Entry> left = new ArrayList<Entry>(which);
        left.removeIf(e -> {
            if (!off.contains(e.name)) return false;
            e.state = new State.Off();
            return true;
        });
        List<Entry> order = new ArrayList<Entry>();
        while (!left.isEmpty()) {
            List<Entry> ready = left.stream().filter(e -> e.description.getDepend().stream()
                    .noneMatch(need -> left.contains(entries.get(need)))).toList();
            if (ready.isEmpty()) break;
            Entry next = ready.stream().filter(e -> e.description.getSoftDepend().stream()
                    .noneMatch(need -> left.contains(entries.get(need)))).findFirst().orElse(ready.getFirst());
            left.remove(next);
            order.add(next);
        }
        for (Entry e : left) {
            fail(e, "depends on itself through " + left.size() + " plugins: " + names(left), null);
        }
        for (Entry e : order) load(e);
        return order;
    }

    private static String names(Collection<Entry> entries) {
        List<String> out = new ArrayList<String>();
        for (Entry e : entries) out.add(e.name);
        return String.join(", ", out);
    }

    /** The first of a plugin's two steps: its own loader, its main class constructed, {@code onLoad} called. */
    private void load(Entry e) {
        if (e.state instanceof State.Running) return;
        if (off.contains(e.name)) {
            e.state = new State.Off();
            return;
        }
        PluginDescription d = e.description;
        String missing = missingDependency(e);
        if (missing != null) {
            fail(e, missing, null);
            return;
        }
        List<PluginLoader> deps = new ArrayList<PluginLoader>();
        for (String need : e.needs()) {
            Entry n = entries.get(need);
            if (n != null && n.state instanceof State.Running) deps.add(((State.Running) n.state).loader);
        }
        File copy = new File(loadedDir, e.name + "-" + (++copies) + ".jar");
        PluginLoader loader;
        try {
            loadedDir.mkdirs();
            Files.copy(e.jar.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            loader = new PluginLoader(d, e.jar, copy, new File(folder, e.name), server, deps, parent);
        } catch (IOException ex) {
            fail(e, "couldn't copy its jar to plugins/.loaded (" + ex.getMessage() + ")", ex);
            return;
        }
        State.Loaded loaded = new State.Loaded(loader);
        e.state = loaded;
        final ClassLoader owner = loader;
        // Pushed before any of the plugin's classes initialize (a static node in a constructor counts), and first,
        // so it runs last: nodes outlive the code that might still check them.
        loaded.undo.push(() -> Node.Lifecycle.release(owner));
        loaded.undo.push(() -> {
            JavaPlugin plugin = loader.plugin();
            if (plugin != null) {
                scheduler.cancelTasks(plugin);
                services.unregisterAll(plugin);
            }
        });
        try {
            if (visibleToMods(d.getMain())) {
                throw new PluginException(d.getMain() + " is also in a jar in mods/, which would run instead. Remove "
                        + "the old mod jar.");
            }
            Class<?> main;
            try {
                main = loader.loadClass(d.getMain());
            } catch (ClassNotFoundException ex) {
                throw new PluginException("plugin.yml's main class " + d.getMain() + " isn't in the jar");
            }
            if (!JavaPlugin.class.isAssignableFrom(main)) {
                throw new PluginException(d.getMain() + " doesn't extend JavaPlugin");
            }
            if (main.getClassLoader() != loader) {
                throw new PluginException("plugin.yml's main class " + d.getMain() + " must be in its own jar, not a dependency");
            }
            final JavaPlugin plugin = (JavaPlugin) main.getConstructor().newInstance();
            for (PluginDescription.PermissionSpec p : d.getPermissions()) {
                Node.Lifecycle.declare(p.node, p.fallback, p.description, loader);
            }
            for (PluginDescription.CommandSpec c : d.getCommands().values()) {
                loader.commands().put(c.name, new PluginCommand(plugin, c));
                if (c.permission != null) Node.Lifecycle.declareImplicit(c.permission, "Use /" + c.name, loader);
            }
            loading = loader;
            try {
                plugin.onLoad();
            } finally {
                loading = null;
            }
        } catch (Throwable t) {
            abandon(e, loaded, t);
        }
    }

    /** The second step: {@code onEnable}, then its plugin.yml commands go live. */
    private void start(Entry e) {
        State.Loaded loaded = (State.Loaded) e.state;
        String missing = missingDependency(e);
        if (missing != null) {
            abandon(e, loaded, new PluginException(missing));
            return;
        }
        if (loaded.loader.hasUnloadedDependencies()) {
            disable(e, new State.Stopped());
            load(e);
            if (e.state instanceof State.Loaded) start(e);
            return;
        }
        PluginLoader loader = loaded.loader;
        State.Enabled running = new State.Enabled(loaded);
        e.state = running;
        long start = System.nanoTime();
        try {
            loader.enable();
            loader.plugin().onEnable();
            for (PluginCommand c : loader.commands().values()) {
                running.undo.push(commands.register(c, loader.logger()));
            }
            enabledOrder.add(e);
            post(new PluginEnableEvent(loader.plugin()));
            LOG.info("Enabled " + e.description.getFullName() + " in " + (System.nanoTime() - start) / 1000000 + " ms");
        } catch (Throwable t) {
            abandon(e, running, t);
        }
    }

    /** Why a hard dependency isn't there to build on, or null when every one is loaded or running. */
    private String missingDependency(Entry e) {
        String version = SharedConstants.getCurrentVersion().getName();
        if (!e.description.getMinecraft().equals(version)) {
            return "was built for Minecraft " + e.description.getMinecraft() + ", but this server runs " + version
                    + ". Rebuild it for " + version + "; docs/porting has what changed";
        }
        String loader = e.description.getLoader();
        if (loader != null && !loader.equals(platform.loader())) {
            return "was built for " + loader + " (it uses a mod directly), but this server runs " + platform.loader()
                    + ". Rebuild it for " + platform.loader() + " against that loader's build of the mod";
        }
        for (String mod : e.description.getModDepend()) {
            if (!platform.isModLoaded(mod)) return "needs the " + mod + " mod, which isn't installed";
        }
        for (String need : e.description.getDepend()) {
            Entry n = entries.get(need);
            if (n == null) return "needs " + need + ", which isn't in plugins/";
            if (!(n.state instanceof State.Running)) return "needs " + n.name + ", which " + describeState(n.state);
        }
        return null;
    }

    /** Undoes a plugin that failed partway through loading or enabling. */
    private void abandon(Entry e, State.Running running, Throwable t) {
        Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        running.loader.beginUnload();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASYNC_GRACE_MILLIS);
        removeEvents(running, deadline);
        JavaPlugin plugin = running.loader.plugin();
        if (plugin != null) scheduler.awaitAsync(plugin, remainingGraceMillis(deadline));
        undoAll(running, e.name);
        if (plugin != null) runDisableHooks(plugin);
        if (enabledOrder.remove(e) && plugin != null) post(new PluginDisableEvent(plugin));
        running.loader.dispose();
        fail(e, cause instanceof PluginException ? cause.getMessage() : "threw " + cause, cause);
    }

    /** Whether a mod jar has the class, which would shadow the plugin's own copy. */
    private boolean visibleToMods(String className) {
        return parent.getResource(className.replace('.', '/') + ".class") != null;
    }

    private void disable(Entry e, State next) {
        if (!(e.state instanceof State.Running)) {
            if (!(e.state instanceof State.Failed) || next instanceof State.Off) e.state = next;
            return;
        }
        State.Running running = (State.Running) e.state;
        JavaPlugin plugin = running.loader.plugin();
        running.loader.beginUnload();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASYNC_GRACE_MILLIS);
        removeEvents(running, deadline);
        if (running instanceof State.Enabled && plugin != null) {
            closeMenus(running.loader);
            int stillRunning = scheduler.awaitAsync(plugin, remainingGraceMillis(deadline));
            if (stillRunning > 0) {
                LOG.warn(e.name + " still had " + stillRunning + " background task(s) running after "
                        + ASYNC_GRACE_MILLIS / 1000 + "s; disabling it anyway");
            }
            try {
                plugin.onDisable();
            } catch (Throwable t) {
                LOG.error(e.name + " threw while disabling", t);
            }
        }
        undoAll(running, e.name);
        if (plugin != null) runDisableHooks(plugin);
        if (enabledOrder.remove(e) && plugin != null) post(new PluginDisableEvent(plugin));
        running.loader.dispose();
        e.state = next;
    }

    /**
     * Closes every menu the plugin's code opened, before onDisable, so the menu's own close handling
     * (handing back items, say) runs as usual and nobody keeps clicking the old version's code after a reload.
     */
    private void closeMenus(PluginLoader loader) {
        for (ServerPlayer p : List.copyOf(minecraft.getPlayerList().getPlayers())) {
            if (p.containerMenu != p.inventoryMenu && p.containerMenu.getClass().getClassLoader() == loader) {
                try {
                    p.closeContainer();
                } catch (RuntimeException ex) {
                    LOG.error("Closing " + p.getGameProfile().getName() + "'s " + loader.description().getName()
                            + " menu failed", ex);
                }
            }
        }
    }

    private static long remainingGraceMillis(long deadline) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static void removeEvents(State.Running running, long deadline) {
        for (EventBus.Subscription subscription : running.subscriptions) subscription.deactivate();
        boolean idle = true;
        for (EventBus.Subscription subscription : running.subscriptions) {
            if (!subscription.awaitIdle(deadline)) idle = false;
        }
        if (!idle) LOG.warn(running.loader.description().getName() + " still had event handlers running after "
                + ASYNC_GRACE_MILLIS / 1000 + "s; disabling it anyway");
    }

    private static void undoAll(State.Running running, String name) {
        while (!running.undo.isEmpty()) {
            try {
                running.undo.pop().run();
            } catch (Throwable t) {
                LOG.error("Undoing part of " + name + " failed", t);
            }
        }
        running.subscriptions.clear();
    }

    private void fail(Entry e, String why, Throwable error) {
        e.state = new State.Failed(why);
        if (error != null && !(error instanceof PluginException)) {
            LOG.error(e.name + " didn't enable: " + why, error);
        } else {
            LOG.error(e.name + " didn't enable: " + why);
        }
        announce(red(e.name + " didn't enable: " + why));
    }

    static String describeState(State s) {
        if (s instanceof State.Enabled) return "is enabled";
        if (s instanceof State.Loaded) return "is still loading";
        if (s instanceof State.Failed) return "didn't enable";
        if (s instanceof State.Off) return "is switched off";
        return "isn't running";
    }

    // ---- What admins do (/plugin) ----

    Collection<Entry> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    Entry entry(String name) {
        return entries.get(name);
    }

    /** Rereads its jar and restarts it with its dependents, or retries one that failed. */
    void reload(final Entry e) {
        manage(new Runnable() {
            @Override
            public void run() {
                if (entries.get(e.name) == e) restart(Collections.singleton(e), "reloaded");
            }
        });
    }

    void reloadAll() {
        manage(new Runnable() {
            @Override
            public void run() {
                restart(new ArrayList<Entry>(entries.values()), "reloaded");
            }
        });
    }

    void switchOff(final Entry e) {
        manage(new Runnable() {
            @Override
            public void run() {
                if (entries.get(e.name) != e) return;
                off.add(e.name);
                saveOff();
                Set<Entry> dependents = dependents(Collections.singleton(e));
                dependents.remove(e);
                disableAll(dependents);
                disable(e, new State.Off());
                announce(gray(e.name + " switched off"));
                if (!dependents.isEmpty()) enableInOrder(new ArrayList<Entry>(dependents));
            }
        });
    }

    /** Switches a plugin back on; one already running is left as it is. */
    void switchOn(final Entry e) {
        manage(new Runnable() {
            @Override
            public void run() {
                if (entries.get(e.name) != e) return;
                if (off.remove(e.name)) saveOff();
                if (e.state instanceof State.Running) return;
                if (e.state instanceof State.Off) e.state = new State.Stopped();
                restart(Collections.singleton(e), "switched on");
            }
        });
    }

    private void readOff() {
        try {
            if (disabledFile.isFile()) {
                for (String line : Files.readAllLines(disabledFile.toPath(), StandardCharsets.UTF_8)) {
                    if (!line.trim().isEmpty()) off.add(line.trim());
                }
            }
        } catch (IOException e) {
            LOG.error("Couldn't read " + disabledFile, e);
        }
    }

    private void saveOff() {
        try {
            folder.mkdirs();
            Files.write(disabledFile.toPath(), off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Couldn't save " + disabledFile, e);
        }
    }

    // ---- PluginManager ----

    /**
     * Throws unless the plugin is the running version and enabled, so an old version kept alive by a stray reference
     * can't register anything after a reload. Safe from any thread.
     */
    void requireActive(JavaPlugin plugin, String what) {
        if (!isActive(plugin)) throw new IllegalStateException(plugin.getName() + " can't " + what + " while it isn't enabled");
    }

    /** Loaded or enabled, and not unloading: services may be registered from onLoad on. For Hadron's own modules too. */
    public boolean isAlive(JavaPlugin plugin) {
        ClassLoader loader = plugin.getClass().getClassLoader();
        return loader instanceof PluginLoader context && context.belongsTo(server)
                && context.plugin() == plugin && context.alive();
    }

    boolean isActive(JavaPlugin plugin) {
        ClassLoader loader = plugin.getClass().getClassLoader();
        return loader instanceof PluginLoader context && context.belongsTo(server)
                && context.plugin() == plugin && context.enabled();
    }

    private State.Enabled running(JavaPlugin plugin, String what) {
        requireServerThread();
        requireActive(plugin, what);
        Entry e = entries.get(plugin.getName());
        if (e == null || !(e.state instanceof State.Enabled)
                || ((State.Enabled) e.state).loader != plugin.getClass().getClassLoader()) {
            throw new IllegalStateException(plugin.getName() + " can't " + what + " while it isn't enabled");
        }
        return (State.Enabled) e.state;
    }

    @Override
    public void registerEvents(Listener listener, JavaPlugin plugin) {
        State.Enabled running = running(plugin, "register listeners");
        for (final EventBus.Subscription s : events.subscribe(listener, plugin.getName(), plugin.getLogger())) {
            running.subscriptions.add(s);
            running.undo.push(new Runnable() {
                @Override
                public void run() {
                    s.remove();
                }
            });
        }
    }

    @Override
    public void unregisterEvents(Listener listener) {
        requireServerThread();
        for (Entry e : entries.values()) {
            if (!(e.state instanceof State.Enabled)) continue;
            for (EventBus.Subscription s : ((State.Enabled) e.state).subscriptions) {
                if (s.listener == listener) s.remove();
            }
        }
    }

    @Override
    public void registerCommand(LiteralArgumentBuilder<CommandSourceStack> command, JavaPlugin plugin) {
        State.Enabled running = running(plugin, "register commands");
        running.undo.push(commands.register(command));
    }

    private void requireServerThread() {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Register or remove listeners and commands on the server thread; use Scheduler.runTask");
        }
    }

    @Override
    public boolean callEvent(Event event) {
        events.post(event);
        return event instanceof Cancellable c && c.isCancelled();
    }

    /** For the loader port: fires a Hadron event from a loader event. */
    public void post(Event event) {
        events.post(event);
    }

    /**
     * Hadron's own modules (the economy, worlds, packets, persistent data) undo what a plugin registered with them
     * here: each hook runs once for every plugin that disables or fails to enable, after the plugin's own undo.
     * Registered once per JVM, so a hook outlives server restarts in singleplayer.
     */
    public static void onPluginDisable(java.util.function.Consumer<JavaPlugin> hook) {
        DISABLE_HOOKS.add(java.util.Objects.requireNonNull(hook, "hook"));
    }

    private static final java.util.List<java.util.function.Consumer<JavaPlugin>> DISABLE_HOOKS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private void runDisableHooks(JavaPlugin plugin) {
        for (java.util.function.Consumer<JavaPlugin> hook : DISABLE_HOOKS) {
            try {
                hook.accept(plugin);
            } catch (Throwable t) {
                LOG.error("Undoing what " + plugin.getName() + " registered with Hadron failed", t);
            }
        }
    }

    /** For the loader port: whether any plugin hears this event class, so a hot chokepoint can skip building it. */
    public boolean listens(Class<? extends Event> type) {
        return events.listens(type);
    }

    /** Loaded or enabled, as in Bukkit; null otherwise. */
    @Override
    public JavaPlugin getPlugin(String name) {
        Entry e = entries.get(name);
        return e != null && e.state instanceof State.Running ? ((State.Running) e.state).loader.plugin() : null;
    }

    @Override
    public List<JavaPlugin> getPlugins() {
        List<JavaPlugin> out = new ArrayList<JavaPlugin>();
        for (Entry e : enabledOrder) out.add(((State.Enabled) e.state).loader.plugin());
        return out;
    }

    @Override
    public boolean isPluginEnabled(String name) {
        Entry e = entries.get(name);
        return e != null && e.state instanceof State.Enabled;
    }

    // ---- Telling people ----

    /** To the console and every online player who may manage plugins. */
    private void announce(Component message) {
        LOG.info(message.getString());
        for (ServerPlayer p : minecraft.getPlayerList().getPlayers()) {
            if (Permissions.has(p, MANAGE)) p.sendSystemMessage(message);
        }
    }

    static MutableComponent green(String text) {
        return Component.literal("✔ " + text).withStyle(ChatFormatting.GREEN);
    }

    static MutableComponent red(String text) {
        return Component.literal("✖ " + text).withStyle(Style.EMPTY.withColor(ChatFormatting.RED)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to see all plugins")))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/plugins")));
    }

    static MutableComponent gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static void deleteChildren(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (!f.delete()) f.deleteOnExit();
    }
}
