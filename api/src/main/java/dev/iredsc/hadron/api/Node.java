package dev.iredsc.hadron.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.minecraft.server.level.ServerPlayer;

/**
 * A permission a plugin checks. Declare each node once, as a constant, and check that constant, so a misspelled node
 * can't compile and a permissions plugin can list every node that exists.
 *
 * <pre>{@code
 * static final Node FLY = Node.declare("myplugin.fly", Default.OPS, "Fly anywhere");
 * if (Permissions.has(player, FLY)) ...
 * }</pre>
 */
public final class Node {
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+(\\.[a-z0-9_-]+)+");
    /**
     * Roots Hadron fills in itself: {@code use.ae2.controller}, {@code command.gamemode} and the like, one per
     * registry id, plus its own {@code hadron.*}. A plugin can't declare under them, so its nodes never collide.
     */
    public static final Set<String> RESERVED_ROOTS =
            Set.of("use", "place", "break", "open", "attack", "command", "packet", "hadron");
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Map<String, Node> declared = new TreeMap<>();
    /**
     * The class loaders of everyone who declared each node. A node goes when its last declarer (a plugin version)
     * unloads. Kept here rather than in the node, so a plugin holding a node never keeps another's loader alive.
     */
    private static final Map<String, Set<Object>> owners = new HashMap<>();
    /** Stands in for code with no class loader of its own, which never unloads. */
    private static final Object PERMANENT = new Object();

    public final String id;
    public final Default fallback;
    /** What holding the node lets a player do, for permissions plugins to list. */
    public final String description;
    /** Declared by Hadron for a plugin.yml command's permission; the plugin's own declaration replaces it. */
    private final boolean implicit;

    private Node(String id, Default fallback, String description, boolean implicit) {
        this.id = id;
        this.fallback = fallback;
        this.description = description;
        this.implicit = implicit;
    }

    /**
     * Declares a node such as {@code homes.set}: lowercase, at least two dot-separated parts, named after the plugin.
     * Declaring the same id twice returns the first node, as long as both agree on who has it by default.
     */
    public static Node declare(String id, Default fallback, String description) {
        ClassLoader caller = WALKER.walk(s -> s.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> c != Node.class).findFirst().map(Class::getClassLoader).orElse(null));
        return declare(id, fallback, description, caller, false);
    }

    private static synchronized Node declare(String id, Default fallback, String description, ClassLoader owner,
            boolean implicit) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Permission node '" + id + "' must look like plugin.thing, lowercase");
        }
        String root = id.substring(0, id.indexOf('.'));
        // Checked under the lock release takes, and a plugin is never alive again once it starts unloading, so a
        // declaration either lands before release removes it or is refused: a stray task can't put one back after.
        if (owner instanceof dev.iredsc.hadron.api.plugin.PluginContext plugin && !plugin.alive()) {
            throw new IllegalStateException(plugin.description().getName() + " declared permission node " + id
                    + " after it began unloading, from code still running once it was disabled. Declare nodes as "
                    + "static constants, and stop background work in onDisable");
        }
        if (owner instanceof dev.iredsc.hadron.api.plugin.PluginContext plugin && RESERVED_ROOTS.contains(root)) {
            throw new dev.iredsc.hadron.api.plugin.PluginException("it declares permission node " + id
                    + ", but '" + root + ".' is reserved for nodes Hadron makes itself. Start it with the plugin's "
                    + "own name, such as " + plugin.description().getName().toLowerCase(java.util.Locale.ROOT) + "."
                    + id.substring(root.length() + 1));
        }
        Node existing = declared.get(id);
        Node node;
        if (existing != null && (implicit || (!existing.implicit && existing.fallback == fallback))) {
            node = existing;
        } else if (existing != null && !existing.implicit) {
            throw new IllegalArgumentException("Permission node " + id + " is declared twice with different "
                    + "defaults: " + existing.fallback + " and " + fallback);
        } else {
            node = new Node(id, fallback, description, implicit);
            declared.put(id, node);
        }
        owners.computeIfAbsent(id, k -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(owner == null ? PERMANENT : owner);
        return node;
    }

    /** For Hadron's plugin host only. */
    public static final class Lifecycle {
        private Lifecycle() {}

        /** A node from a plugin.yml {@code permissions:} entry. */
        public static Node declare(String id, Default fallback, String description, ClassLoader owner) {
            return Node.declare(id, fallback, description, owner, false);
        }

        /** A plugin.yml command's permission nothing declared: operators only, as in Bukkit. */
        public static Node declareImplicit(String id, String description, ClassLoader owner) {
            return Node.declare(id, Default.OPS, description, owner, true);
        }

        /** Forgets an unloaded plugin version's declarations; nodes nobody else declared go. */
        public static void release(ClassLoader owner) {
            synchronized (Node.class) {
                owners.entrySet().removeIf(e -> {
                    if (!e.getValue().remove(owner) || !e.getValue().isEmpty()) return false;
                    declared.remove(e.getKey());
                    return true;
                });
            }
        }
    }

    /** Every declared node, sorted by id. */
    public static synchronized List<Node> all() {
        return List.copyOf(new ArrayList<>(declared.values()));
    }

    /** Null when nothing declared it. */
    public static synchronized Node get(String id) {
        return declared.get(id);
    }

    public boolean allows(ServerPlayer player) {
        return Permissions.has(player, this);
    }

    @Override
    public String toString() {
        return id;
    }
}
