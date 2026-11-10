package dev.iredsc.hadron.common;

import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.function.Supplier;
import dev.iredsc.hadron.api.bridge.Bridge;
import dev.iredsc.hadron.api.event.entity.EntityDataChangeEvent;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    public static final Node TRACE = Node.declare("hadron.trace", Default.OPS,
            "Watch events fire live with /hadron trace: who did it, and what plugins made of it");
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
            final @Nullable String why;
            Failed(@Nullable String why) {
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
        @Nullable Stamp stamp;
        /**
         * What was loaded: the jar file version (as the host stamped it) and the hash of the copy made from it. Null
         * until a load copies a jar. It stays the running jar's while a replacement is refused, though {@link #stamp}
         * moves on to the refused one so it isn't tried again every poll.
         */
        @Nullable Source source;
        /** The snapshot the current load attempt reads from, taken at its start and used up by {@link #load}. */
        Snapshot.@Nullable Usable attempt;
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

    /** One jar version an outcome is about: its file name, its stamp then, and the SHA-256 of those bytes. */
    record Source(String jarName, Stamp stamp, String sha256) {}

    /**
     * One immutable view of a jar, taken once by {@link #snapshot}: a private copy, and everything read from that copy
     * and from nothing else, so no part of an attempt can come from a different version of the file in plugins/ than
     * another part. Every decision about the attempt (what the plugin is called, what it needs, which class is its
     * main, which jar a status line is about) reads the snapshot; the live file is never read again for it.
     */
    sealed interface Snapshot {
        /** The jar this snapshot is of: its name, its file stamp around the copy, and the hash of the copy. */
        Source source();

        /** A copy whose plugin.yml was read: the plugin runs from {@code copy}. */
        record Usable(File copy, Source source, PluginDescription description) implements Snapshot {}

        /** A jar that can't become a plugin, and why; there is no copy left. */
        record Unusable(Source source, String why) implements Snapshot {}
    }

    /** A refused jar's reason, and the jar version it was refused at. */
    private record Refusal(Source source, String why) {}

    /** A jar's modification time and size; equal stamps mean an unchanged file. */
    static final class Stamp {
        final long modified;
        final long size;

        Stamp(File file) {
            modified = file.lastModified();
            size = file.length();
        }

        @Override
        public boolean equals(@Nullable Object o) {
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
    private final Trace trace;
    private final Commands commands;
    private final Map<String, Entry> entries = new TreeMap<String, Entry>(String.CASE_INSENSITIVE_ORDER);
    private final List<Entry> enabledOrder = new ArrayList<Entry>();
    private final Set<String> off = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    /** Jars seen changed once; acted on when the next poll finds them the same, so a half-copied jar waits. */
    private final Map<File, Stamp> settling = new HashMap<File, Stamp>();
    /** Jars that can't be used as they are (duplicate name, unreadable), by the stamp they were refused at. */
    private final Map<File, Stamp> refused = new HashMap<File, Stamp>();
    /** Why each refused jar was, and its hash, for {@code /hadron status}; the same keys as {@link #refused}. */
    private final Map<File, Refusal> refusals = new HashMap<File, Refusal>();
    private int copies;
    private int ticks;
    private boolean running;
    /** The plugin whose onLoad is running: the only time a plugin may register placeholders. */
    private volatile @Nullable PluginLoader loading;
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
        this.trace = new Trace(events, minecraft);
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

    Trace trace() {
        return trace;
    }

    /** A recording started by {@link #recordEvents}; closing it stops it. */
    public interface EventRecording extends AutoCloseable {
        @Override
        void close();
    }

    /** Where {@link #recordEvents} hands each event. */
    public interface EventSink {
        /**
         * Called once every handler has run. {@code firedAt} is what the recording's clock read when the event fired,
         * before any handler, so a handler that waits doesn't move it. May be called from any thread.
         */
        void heard(Event event, int firedAt, String line);
    }

    /**
     * For the test runner: hands {@code sink} every event some plugin handled, after they all had, with the line
     * {@code /hadron trace} would show for it and {@code clock}'s reading when it fired, until the recording is
     * closed. It costs only the events that are built anyway: an event nobody listens to stays unbuilt.
     */
    public EventRecording recordEvents(java.util.function.IntSupplier clock, EventSink sink) {
        TraceRecorder recorder = new TraceRecorder(trace, events, clock, sink);
        events.observe(recorder);
        return recorder;
    }

    /** Every event class asked about since start, for {@code /hadron trace} names. */
    java.util.Set<Class<?>> seenEvents() {
        return events.seen();
    }

    /** A plugin's own event class by its full name, from whichever running plugin has it; null if none does. */
    @Nullable Class<?> pluginClass(String name) {
        for (Entry e : entries.values()) {
            if (!(e.state instanceof State.Running running)) continue;
            try {
                return Class.forName(name, false, running.loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // not this plugin's
            }
        }
        return null;
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
        trace.tick();
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
        trace.stopAll();
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
    private @Nullable Entry adopt(File jar, Stamp scanned) {
        Snapshot seen = snapshot(jar);
        delete(seen); // only the name and the verdict matter here: a load takes its own snapshot
        if (!(seen instanceof Snapshot.Usable usable)) {
            refuse(jar, seen.source(), jar.getName() + " isn't a usable plugin: " + ((Snapshot.Unusable) seen).why());
            return null;
        }
        PluginDescription description = usable.description();
        Entry existing = entries.get(description.getName());
        if (existing != null && !existing.jar.equals(jar)) {
            refuse(jar, seen.source(), jar.getName() + " is ignored: " + existing.jar.getName() + " is already "
                    + description.getName() + ". Delete one of them.");
            return null;
        }
        refused.remove(jar);
        refusals.remove(jar);
        Entry e = new Entry(description.getName(), jar, seen.source().stamp(), description);
        entries.put(e.name, e);
        return e;
    }

    /** Records that this version of the jar can't be used, with the identity of the snapshot that showed it. */
    private void refuse(File jar, Source seen, String why) {
        refused.put(jar, seen.stamp());
        refusals.put(jar, new Refusal(seen, why));
        LOG.error(why);
        announce(red(why));
    }

    /**
     * The one way a jar is read: copies it to a file of its own in {@code plugins/.loaded}, then reads the description
     * and the hash from that copy. The file's stamp is taken around the copy: if it moved meanwhile the copy is taken
     * again (a few times), and a jar that never holds still has an {@link PluginStatus#UNKNOWN} hash, which matches no
     * deploy. A jar that can't be copied or described comes back {@link Snapshot.Unusable} with the hash of what was
     * copied, so the failure is about that version.
     */
    private Snapshot snapshot(File jar) {
        File copy = new File(loadedDir, jar.getName() + "-" + (++copies) + ".jar");
        Stamp stamp = new Stamp(jar);
        boolean steady = false;
        try {
            loadedDir.mkdirs();
            for (int tries = 0; tries < 3 && !steady; tries++) {
                stamp = new Stamp(jar);
                Files.copy(jar.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
                steady = stamp.equals(new Stamp(jar));
            }
        } catch (IOException ex) {
            // An earlier try may have copied before this one failed; Unusable carries no copy, so nothing else can.
            delete(copy);
            return new Snapshot.Unusable(new Source(jar.getName(), stamp, PluginStatus.UNKNOWN),
                    "couldn't copy its jar to plugins/.loaded (" + ex.getMessage() + ")");
        }
        Source source = new Source(jar.getName(), stamp, steady ? sha256(copy) : PluginStatus.UNKNOWN);
        try {
            return new Snapshot.Usable(copy, source, describe(copy));
        } catch (PluginException ex) {
            delete(copy);
            return new Snapshot.Unusable(source, String.valueOf(ex.getMessage()));
        }
    }

    private static void delete(File copy) {
        try {
            Files.deleteIfExists(copy.toPath());
        } catch (IOException ex) {
            LOG.warn("Couldn't delete " + copy + ": " + ex.getMessage());
        }
    }

    /** Drops a snapshot that nothing will run from. */
    private static void delete(Snapshot snapshot) {
        if (snapshot instanceof Snapshot.Usable usable) delete(usable.copy());
    }

    /** Drops the entry's unused snapshot, if it has one. */
    private static void discard(Entry e) {
        Snapshot.Usable unused = e.attempt;
        e.attempt = null;
        if (unused != null) delete(unused.copy());
    }

    /** Reads a snapshot's plugin.yml. Only {@link #snapshot} calls it, on its own copy. */
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
        // A refusal is about one file version: once the file is replaced or gone, its status line goes too.
        refusals.entrySet().removeIf(r -> !r.getValue().source().stamp().equals(now.get(r.getKey())));
        // A new jar with a running plugin's name replaces it in place (a deploy renames the file, version or not),
        // so the old version keeps running until the new one is ready instead of its dependents losing it.
        Map<String, File> newByName = new TreeMap<String, File>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<File, Stamp> jar : now.entrySet()) {
            if (entryFor(jar.getKey()) != null || jar.getValue().equals(refused.get(jar.getKey()))) continue;
            Snapshot seen = snapshot(jar.getKey());
            delete(seen);
            if (seen instanceof Snapshot.Usable usable) newByName.put(usable.description().getName(), jar.getKey());
            // Otherwise half copied, or broken: the settle step below deals with it.
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

    private @Nullable Entry entryFor(File jar) {
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
        Snapshot seen = snapshot(jar);
        delete(seen); // a reload takes its own snapshot; this one decides only whether to try
        if (!(seen instanceof Snapshot.Usable usable)) {
            // The old version keeps running until the jar is fixed.
            refuse(jar, seen.source(), jar.getName() + " changed but isn't usable, so the running version stays: "
                    + ((Snapshot.Unusable) seen).why());
            e.stamp = seen.source().stamp();
            return;
        }
        if (!usable.description().getName().equalsIgnoreCase(e.name)) {
            remove(e);
            added(jar, stamp);
            return;
        }
        e.stamp = seen.source().stamp();
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
        long start = logClock();
        enableInOrder(new ArrayList<Entry>(all)); // each takes its own snapshot of its jar, and fails with it if unusable
        long ms = (logClock() - start) / 1000000;
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
        // Every jar is snapshotted before anything loads, so no plugin's onLoad can change what another is read from.
        List<Entry> left = new ArrayList<Entry>();
        for (Entry e : which) if (attempt(e)) left.add(e);
        left.removeIf(e -> {
            if (!off.contains(e.name)) return false;
            discard(e);
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
            discard(e);
            fail(e, unstartable(e, left), null);
        }
        for (Entry e : order) load(e);
        return order;
    }

    /** Why a plugin left over by dependency ordering can't start: it is in a cycle, or waits on one. */
    private static String unstartable(Entry e, List<Entry> left) {
        Map<String, List<String>> waiting = new LinkedHashMap<>();
        for (Entry o : left) waiting.put(o.name, o.description.getDepend());
        return DependencyCycles.reason(e.name, waiting);
    }


    /**
     * Starts a load attempt: takes the one snapshot of the entry's jar that the attempt reads, and sets from it the
     * entry's description (what it is called, what it needs) and the identity its status lines report. A jar that
     * can't be used fails the entry, with that snapshot's identity. @return whether the entry may go on.
     */
    private boolean attempt(Entry e) {
        discard(e);
        Snapshot snapshot = snapshot(e.jar);
        e.source = snapshot.source();
        if (snapshot instanceof Snapshot.Unusable unusable) {
            fail(e, unusable.why(), null);
            return false;
        }
        Snapshot.Usable usable = (Snapshot.Usable) snapshot;
        if (!usable.description().getName().equalsIgnoreCase(e.name)) {
            delete(usable);
            fail(e, "its plugin.yml now names it " + usable.description().getName(), null);
            return false;
        }
        e.description = usable.description();
        e.attempt = usable;
        return true;
    }

    /** The first of a plugin's two steps: its own loader, its main class constructed, {@code onLoad} called. */
    private void load(Entry e) {
        if (e.state instanceof State.Running) {
            discard(e);
            return;
        }
        if (e.attempt == null && !attempt(e)) return;
        Snapshot.Usable snapshot = Objects.requireNonNull(e.attempt);
        e.attempt = null;
        if (off.contains(e.name)) {
            delete(snapshot);
            e.state = new State.Off();
            return;
        }
        PluginDescription d = snapshot.description();
        String missing = missingDependency(e);
        if (missing != null) {
            delete(snapshot);
            fail(e, missing, null);
            return;
        }
        List<PluginLoader> deps = new ArrayList<PluginLoader>();
        for (String need : e.needs()) {
            Entry n = entries.get(need);
            if (n != null && n.state instanceof State.Running) deps.add(((State.Running) n.state).loader);
        }
        File data;
        try {
            data = dataFolder(folder, e.name);
        } catch (PluginException ex) {
            delete(snapshot);
            fail(e, ex.getMessage(), null);
            return;
        }
        PluginLoader loader;
        try {
            loader = new PluginLoader(d, e.jar, snapshot.copy(), data, server, deps, parent);
        } catch (IOException ex) {
            delete(snapshot);
            fail(e, "couldn't load its jar from plugins/.loaded (" + ex.getMessage() + ")", ex);
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
        String step = "loading";
        try {
            if (visibleToMods(d.getMain())) {
                throw new PluginException(d.getMain() + " is also in a jar in mods/, which would run instead. Remove "
                        + "the old mod jar.");
            }
            Class<?> main;
            try {
                main = loader.loadClass(d.getMain());
            } catch (ClassNotFoundException ex) {
                throw new PluginException("plugin.yml's main class " + d.getMain() + " isn't in the jar. main: must "
                        + "be the class's full name, package included, such as main: com.example.myplugin.MyPlugin, "
                        + "and that class must be built into this jar");
            }
            if (!JavaPlugin.class.isAssignableFrom(main)) {
                throw new PluginException(d.getMain() + " doesn't extend JavaPlugin. Declare it as public class "
                        + main.getSimpleName() + " extends " + JavaPlugin.class.getName());
            }
            if (main.getClassLoader() != loader) {
                throw new PluginException("plugin.yml's main class " + d.getMain() + " must be in its own jar, not a dependency");
            }
            final JavaPlugin plugin = (JavaPlugin) construct(main);
            step = "declaring its permissions and commands";
            for (PluginDescription.PermissionSpec p : d.getPermissions()) {
                Node.Lifecycle.declare(p.node, p.fallback, p.description, loader);
            }
            for (PluginDescription.CommandSpec c : d.getCommands().values()) {
                loader.commands().put(c.name, new PluginCommand(plugin, c));
                if (c.permission != null) Node.Lifecycle.declareImplicit(c.permission, "Use /" + c.name, loader);
            }
            step = "onLoad";
            loading = loader;
            try {
                plugin.onLoad();
            } finally {
                loading = null;
            }
        } catch (Throwable t) {
            abandon(e, loaded, t, step);
        }
    }

    /**
     * Makes the plugin's main class with its public no-argument constructor, the one way Hadron can. A constructor
     * that throws comes out as {@link ConstructorThrew}, so the message can say it was the constructor.
     */
    private static Object construct(Class<?> main) {
        java.lang.reflect.Constructor<?> constructor;
        try {
            constructor = main.getConstructor();
        } catch (NoSuchMethodException ex) {
            throw new PluginException(main.getName() + " needs a public constructor that takes no arguments, which "
                    + "Hadron creates the plugin with. Add public " + main.getSimpleName() + "() {}, and move "
                    + "any setup into onLoad or onEnable");
        }
        if (java.lang.reflect.Modifier.isAbstract(main.getModifiers())) {
            throw new PluginException(main.getName() + " is abstract, so Hadron can't create it. Make the class "
                    + "plugin.yml's main names a concrete one");
        }
        try {
            return constructor.newInstance();
        } catch (InvocationTargetException ex) {
            throw new ConstructorThrew(ex.getCause() != null ? ex.getCause() : ex);
        } catch (IllegalAccessException | InstantiationException ex) {
            throw new PluginException(main.getName() + " can't be created (" + ex.getMessage() + "). Make it a public, "
                    + "top-level class with a public constructor that takes no arguments");
        }
    }

    /** The plugin's own constructor threw {@link #getCause()}. */
    private static final class ConstructorThrew extends RuntimeException {
        final Throwable thrown;

        ConstructorThrew(Throwable thrown) {
            super(thrown);
            this.thrown = thrown;
        }
    }

    /** The second step: {@code onEnable}, then its plugin.yml commands go live. */
    private void start(Entry e) {
        State.Loaded loaded = (State.Loaded) e.state;
        String missing = missingDependency(e);
        if (missing != null) {
            abandon(e, loaded, new PluginException(missing), "starting");
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
        long start = logClock();
        String step = "enabling";
        try {
            loader.enable();
            step = "onEnable";
            JavaPlugin enabling = loader.attached();
            PluginActors.asPlugin(enabling.getName(), enabling::onEnable);
            step = "registering its commands";
            for (PluginCommand c : loader.commands().values()) {
                running.undo.push(commands.register(c, loader.logger()));
            }
            enabledOrder.add(e);
            post(new PluginEnableEvent(loader.attached()));
            LOG.info("Enabled " + e.description.getFullName() + " in " + (logClock() - start) / 1000000 + " ms");
        } catch (Throwable t) {
            abandon(e, running, t, step);
        }
    }

    /** Why a hard dependency isn't there to build on, or null when every one is loaded or running. */
    private @Nullable String missingDependency(Entry e) {
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
        for (String mod : e.description.getApiDepend()) {
            if (!platform.isModLoaded(mod)) {
                return "uses the " + mod + " mod's API, but that mod isn't installed: add " + mod + " to mods/, or "
                        + "remove this plugin";
            }
        }
        for (String mod : e.description.getBridgeDepend()) {
            if (Bridge.isCoveredByCore(mod)) {
                return "lists " + mod + " under bridge-depend, but core covers " + mod + " itself and no bridge for "
                        + "it exists: remove " + mod + " from bridge-depend";
            }
            List<String> bridged = Bridge.modsBridgedBy(mod);
            if (!bridged.isEmpty()) {
                return "lists " + mod + " under bridge-depend, but " + mod + " is a bridge itself: list the mod it "
                        + "bridges instead, bridge-depend: [" + bridged.get(0) + "]";
            }
            String why = missingBridge(mod, platform.isModLoaded(mod), Bridge.isBridged(mod));
            if (why != null) return why;
        }
        for (String need : e.description.getDepend()) {
            Entry n = entries.get(need);
            if (n == null) {
                return "needs " + need + ", which isn't in plugins/: put " + need + "'s jar in plugins/, or, if it "
                        + "can run without it, move " + need + " from depend: to softdepend: in its plugin.yml";
            }
            if (!(n.state instanceof State.Running)) return "needs " + n.name + ", which " + describeState(n.state);
        }
        return null;
    }

    /** Why a bridge-depend on {@code mod} can't be met, or null when its bridge is loaded. */
    static @Nullable String missingBridge(String mod, boolean modLoaded, boolean bridgeLoaded) {
        if (bridgeLoaded) return null;
        if (!modLoaded) {
            return "uses Hadron's bridge for the " + mod + " mod, but " + mod + " isn't installed: add " + mod
                    + " and its Hadron bridge to mods/, or remove this plugin";
        }
        return "uses Hadron's bridge for " + mod + ", which isn't installed: add the Hadron bridge for " + mod
                + " to mods/, or remove this plugin";
    }

    /**
     * Undoes a plugin that failed partway through loading or enabling, during {@code step} ("its constructor",
     * "onLoad", "onEnable"...). A refusal says its own fix; anything the plugin threw is named with the step and the
     * plugin's own line it came from, its stack going to the log.
     */
    private void abandon(Entry e, State.Running running, Throwable t, String step) {
        Throwable cause = t;
        if (cause instanceof ConstructorThrew c) {
            step = "its constructor";
            cause = c.thrown;
        }
        if (cause instanceof InvocationTargetException && cause.getCause() instanceof Throwable inner) cause = inner;
        String why = cause instanceof PluginException ? cause.getMessage()
                : step + " threw " + cause + pluginFrame(cause, running.loader) + ". Fix that there; the stack trace "
                        + "is in the server log";
        teardown(e, running, new State.Failed(why), false);
        report(e, why, cause);
    }

    /** " at File.java:line", the first frame in the plugin's own code, or "" when none of the trace is its. */
    static String pluginFrame(Throwable t, ClassLoader loader) {
        String name = loader.getName();
        for (StackTraceElement frame : t.getStackTrace()) {
            if (name != null && name.equals(frame.getClassLoaderName())) {
                return " at " + frame.getClassName() + "." + frame.getMethodName() + "(" + frame.getFileName() + ":"
                        + frame.getLineNumber() + ")";
            }
        }
        return "";
    }

    /**
     * {@code plugins/<name>/}, refused unless it is a folder of its own directly in {@code plugins/}: never the jar
     * cache Hadron empties, the switched-off list, or a path out of {@code plugins/}. plugin.yml's name check already
     * keeps such names out; this keeps the host safe whatever reaches it.
     */
    static File dataFolder(File plugins, String name) {
        java.nio.file.Path root = plugins.toPath().toAbsolutePath().normalize();
        java.nio.file.Path dir = root.resolve(name).normalize();
        if (!root.equals(dir.getParent()) || name.equalsIgnoreCase(".loaded") || name.equalsIgnoreCase("disabled.txt")
                || name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            throw new PluginException("its name '" + name + "' can't be its data folder in plugins/. Rename it in "
                    + "plugin.yml to start with a letter or digit, such as name: MyPlugin");
        }
        return dir.toFile();
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
        teardown(e, (State.Running) e.state, next, true);
    }

    /**
     * Stops a running version, the one way a plugin stops whether it disables or fails to enable, so both undo the
     * same things: its listeners, its menus, its background work, everything it registered, and its classes on the
     * bus. The entry moves to {@code next} before anything is told, so a {@link PluginDisableEvent} listener already
     * finds the plugin disabled and gone.
     */
    private void teardown(Entry e, State.Running running, State next, boolean callOnDisable) {
        JavaPlugin plugin = running.loader.plugin();
        running.loader.beginUnload();
        long deadline = graceDeadline();
        removeEvents(running, deadline);
        if (plugin != null) {
            closeMenus(running.loader);
            int stillRunning = scheduler.awaitAsync(plugin, remainingGraceMillis(deadline));
            if (stillRunning > 0) {
                LOG.warn(e.name + " still had " + stillRunning + " background task(s) running after "
                        + ASYNC_GRACE_MILLIS / 1000 + "s; " + (callOnDisable ? "disabling" : "stopping") + " it anyway. "
                        + "A background task that loops should return once isEnabled() is false");
            }
            runBeforeDisableHooks(plugin, deadline);
            if (callOnDisable && running instanceof State.Enabled) {
                try {
                    PluginActors.asPlugin(plugin.getName(), plugin::onDisable);
                } catch (Throwable t) {
                    LOG.error(e.name + " threw while disabling", t);
                }
            }
        }
        boolean wasEnabled = enabledOrder.remove(e);
        e.state = next;
        undoAll(running, e.name);
        if (plugin != null) runDisableHooks(plugin);
        if (wasEnabled && plugin != null) post(new PluginDisableEvent(plugin));
        running.loader.dispose();
        events.forget(running.loader);
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

    /** Only for "Enabled X in N ms": people read it, nothing decides on it. */
    @Nondeterministic(reason = "times a step only to print how long it took")
    private static long logClock() {
        return System.nanoTime();
    }

    /** When a disabling plugin's async tasks and off-thread handlers stop being waited for. */
    @Nondeterministic(reason = "a grace period for work on other threads, which server ticks don't measure")
    private static long graceDeadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASYNC_GRACE_MILLIS);
    }

    @Nondeterministic(reason = "a grace period for work on other threads, which server ticks don't measure")
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
        for (EventBus.Subscription s : running.subscriptions) s.remove();
        while (!running.undo.isEmpty()) {
            try {
                running.undo.pop().run();
            } catch (Throwable t) {
                LOG.error("Undoing part of " + name + " failed", t);
            }
        }
        running.subscriptions.clear();
    }

    private void fail(Entry e, @Nullable String why, @Nullable Throwable error) {
        e.state = new State.Failed(why);
        report(e, why, error);
    }

    private void report(Entry e, @Nullable String why, @Nullable Throwable error) {
        if (error != null && !(error instanceof PluginException)) {
            LOG.error(e.name + " didn't enable: " + why, error);
        } else {
            LOG.error(e.name + " didn't enable: " + why);
        }
        announce(red(e.name + " didn't enable: " + why));
    }

    /**
     * Every plugin and every refused jar as plain text, for {@code /hadron status}. A plugin's file version and hash
     * are those of the jar its outcome is about, recorded when the host decided it (it loaded the jar, or failed or
     * switched it off before loading), even while a replacement is refused (that one is a line of its own). Nothing
     * here reads a jar from disk: an identity read now would belong to whatever file is there now.
     */
    List<PluginStatus> statuses() {
        List<PluginStatus> out = new ArrayList<PluginStatus>();
        for (Entry e : entries.values()) {
            Source source = e.source; // recorded when the outcome was decided; null only before any decision
            long modified = source == null ? 0 : source.stamp().modified;
            String sha = source == null ? PluginStatus.UNKNOWN : source.sha256();
            PluginStatus.State state = e.state instanceof State.Enabled ? PluginStatus.State.ENABLED
                    : e.state instanceof State.Loaded ? PluginStatus.State.LOADING
                    : e.state instanceof State.Failed ? PluginStatus.State.FAILED
                    : e.state instanceof State.Off ? PluginStatus.State.OFF : PluginStatus.State.STOPPED;
            String why = e.state instanceof State.Failed f ? (f.why == null ? "no reason was given" : f.why) : null;
            out.add(new PluginStatus(e.name, state, source == null ? e.jar.getName() : source.jarName(), modified, sha, why));
        }
        for (Refusal refusal : refusals.values()) {
            Source seen = refusal.source();
            out.add(new PluginStatus(null, PluginStatus.State.REFUSED, seen.jarName(), seen.stamp().modified,
                    seen.sha256(), refusal.why()));
        }
        return out;
    }

    /** Hex SHA-256 of a file's bytes, or {@link PluginStatus#UNKNOWN} if it can't be read. */
    private static String sha256(File file) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (IOException | java.security.NoSuchAlgorithmException ex) {
            return PluginStatus.UNKNOWN;
        }
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

    @Nullable Entry entry(String name) {
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

    /**
     * For the test runner: only {@code keep} (and what it needs) runs while its tests do; every plugin named in
     * {@code others} is disabled, with whatever depends on it, so one suite's listeners can't change another's
     * results. {@code keep} is started again if an earlier suite's turn disabled it. Not saved: nothing outlives the run.
     */
    public void isolateForTest(String keep, java.util.Collection<String> others) {
        manage(new Runnable() {
            @Override
            public void run() {
                Set<Entry> stop = new LinkedHashSet<Entry>();
                for (String name : others) {
                    Entry e = entries.get(name);
                    if (e != null && !name.equals(keep)) stop.add(e);
                }
                Entry kept = entries.get(keep);
                Set<Entry> affected = dependents(stop);
                affected.remove(kept);
                disableAll(affected);
                if (kept != null && !(kept.state instanceof State.Running)) {
                    restart(Collections.singleton(kept), "under test");
                }
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
        // Kept only in the plugin's list, which unregistering and disabling empty: an undo entry per subscription
        // would hold every listener the plugin ever unregistered, and what it holds, until the plugin disabled.
        running.subscriptions.addAll(events.subscribe(listener, plugin.getName(), plugin.getLogger()));
    }

    @Override
    public void unregisterEvents(Listener listener) {
        requireServerThread();
        for (Entry e : entries.values()) {
            if (!(e.state instanceof State.Enabled)) continue;
            unregister(((State.Enabled) e.state).subscriptions, listener);
        }
    }

    /**
     * Removes the listener's subscriptions, and forgets those nothing needs any more: one whose handler is still
     * running on another thread stays listed, so disabling the plugin still waits for it before onDisable, and goes
     * at the next unregister or the disable.
     */
    static void unregister(List<EventBus.Subscription> subscriptions, Listener listener) {
        for (EventBus.Subscription s : subscriptions) if (s.listener == listener) s.remove();
        subscriptions.removeIf(EventBus.Subscription::finished);
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
        if (event instanceof EntityDataChangeEvent) {
            throw new IllegalArgumentException("EntityDataChangeEvent is fired by a mod's bridge, through its claim, "
                    + "which checks the kind is that mod's: fire it with BRIDGE.fire(event), where BRIDGE is what "
                    + "Bridge.claim returned in your bridge's constructor");
        }
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

    @StaticState(reason = "Hadron's own modules register these once when the mod loads; plugins never do")
    private static final java.util.List<java.util.function.Consumer<JavaPlugin>> DISABLE_HOOKS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Hadron's modules that call plugin code off the server thread (packet handlers) wait here for those calls to
     * finish, so none overlaps the plugin's onDisable. Each hook runs once the plugin no longer counts as enabled and
     * before onDisable, with the milliseconds left of the disable grace period; it must return by then.
     */
    public static void beforePluginDisable(java.util.function.ObjLongConsumer<JavaPlugin> hook) {
        BEFORE_DISABLE_HOOKS.add(java.util.Objects.requireNonNull(hook, "hook"));
    }

    @StaticState(reason = "Hadron's own modules register these once when the mod loads; plugins never do")
    private static final java.util.List<java.util.function.ObjLongConsumer<JavaPlugin>> BEFORE_DISABLE_HOOKS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private void runBeforeDisableHooks(JavaPlugin plugin, long deadline) {
        for (java.util.function.ObjLongConsumer<JavaPlugin> hook : BEFORE_DISABLE_HOOKS) {
            try {
                hook.accept(plugin, remainingGraceMillis(deadline));
            } catch (Throwable t) {
                LOG.error("Waiting for " + plugin.getName() + "'s calls in progress failed", t);
            }
        }
    }

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
    public @Nullable JavaPlugin getPlugin(String name) {
        Entry e = entries.get(name);
        return e != null && e.state instanceof State.Running ? ((State.Running) e.state).loader.plugin() : null;
    }

    @Override
    public List<JavaPlugin> getPlugins() {
        List<JavaPlugin> out = new ArrayList<JavaPlugin>();
        for (Entry e : enabledOrder) out.add(((State.Enabled) e.state).loader.attached());
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
        if (minecraft == null) return;
        // Telling people never stops what is being told: a plugin's failure here can't halt a reload partway.
        for (ServerPlayer p : List.copyOf(minecraft.getPlayerList().getPlayers())) {
            try {
                if (Permissions.has(p, MANAGE)) p.sendSystemMessage(message);
            } catch (RuntimeException | LinkageError ex) {
                LOG.error("Telling " + p.getGameProfile().getName() + " \"" + message.getString() + "\" failed", ex);
            }
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
