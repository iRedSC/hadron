package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Who can't see what, per plugin, by UUID. Written on the server thread, read from any thread (the send hook runs on
 * whichever thread sends), so every set is concurrent and the plugin list is a copied array. While nothing is hidden,
 * every check is one volatile read.
 */
final class NetVisibility {
    private NetVisibility() {}

    /** One plugin's hides. */
    static final class Rules {
        final JavaPlugin plugin;
        /** Hidden from every viewer, except those in {@link #shown}. */
        final Set<UUID> fromAll = ConcurrentHashMap.newKeySet();
        /** Viewer to the targets hidden from them alone. */
        final Map<UUID, Set<UUID>> hidden = new ConcurrentHashMap<UUID, Set<UUID>>();
        /** Viewer to the targets in {@link #fromAll} they may see anyway. */
        final Map<UUID, Set<UUID>> shown = new ConcurrentHashMap<UUID, Set<UUID>>();

        Rules(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        boolean hides(UUID viewer, UUID target) {
            if (fromAll.contains(target)) {
                Set<UUID> s = shown.get(viewer);
                return s == null || !s.contains(target);
            }
            Set<UUID> h = hidden.get(viewer);
            return h != null && h.contains(target);
        }

        boolean empty() {
            return fromAll.isEmpty() && hidden.isEmpty();
        }

        /** Every target this plugin hides from someone. */
        Set<UUID> targets() {
            Set<UUID> out = new HashSet<UUID>(fromAll);
            for (Set<UUID> s : hidden.values()) out.addAll(s);
            return out;
        }

        static void add(Map<UUID, Set<UUID>> map, UUID viewer, UUID target) {
            map.computeIfAbsent(viewer, k -> ConcurrentHashMap.newKeySet()).add(target);
        }

        static void remove(Map<UUID, Set<UUID>> map, UUID viewer, UUID target) {
            Set<UUID> s = map.get(viewer);
            if (s != null && s.remove(target) && s.isEmpty()) map.remove(viewer);
        }

        static void removeEverywhere(Map<UUID, Set<UUID>> map, UUID target) {
            map.values().removeIf(s -> s.remove(target) && s.isEmpty());
        }
    }

    /** Server thread only. */
    private static final Map<JavaPlugin, Rules> BY_PLUGIN = new IdentityHashMap<JavaPlugin, Rules>();
    private static volatile Rules[] rules = new Rules[0];
    private static volatile boolean active;

    static boolean active() {
        return active;
    }

    static boolean canSee(UUID viewer, UUID target) {
        if (!active || viewer.equals(target)) return true;
        for (Rules r : rules) {
            if (r.hides(viewer, target)) return false;
        }
        return true;
    }

    static boolean canSee(ServerPlayer viewer, Entity target) {
        if (!active || viewer == target) return true;
        return canSee(viewer.getUUID(), target.getUUID());
    }

    static boolean isHiddenFromAll(JavaPlugin plugin, Entity target) {
        UUID t = need(target).getUUID();
        for (Rules r : rules) {
            if (r.plugin == plugin) return r.fromAll.contains(t);
        }
        return false;
    }

    private static Entity need(Entity target) {
        if (target == null) throw new IllegalArgumentException("Visibility needs an entity to hide or show; it was null");
        return target;
    }

    // ---- Changes (server thread) ----

    static void hide(JavaPlugin plugin, ServerPlayer viewer, Entity target) {
        if (need(target) == viewer) return;
        Rules r = rules(plugin, "hide entities");
        UUID v = viewer.getUUID(), t = target.getUUID();
        change(List.of(viewer), target, () -> {
            Rules.add(r.hidden, v, t);
            Rules.remove(r.shown, v, t);
        });
    }

    static void show(JavaPlugin plugin, ServerPlayer viewer, Entity target) {
        if (need(target) == viewer) return;
        Rules r = existing(plugin);
        if (r == null) return;
        UUID v = viewer.getUUID(), t = target.getUUID();
        change(List.of(viewer), target, () -> {
            Rules.remove(r.hidden, v, t);
            if (r.fromAll.contains(t)) Rules.add(r.shown, v, t);
        });
    }

    static void hideFromAll(JavaPlugin plugin, Entity target) {
        need(target);
        Rules r = rules(plugin, "hide entities");
        UUID t = target.getUUID();
        change(NetPort.server().getPlayerList().getPlayers(), target, () -> {
            r.fromAll.add(t);
            Rules.removeEverywhere(r.shown, t);
        });
    }

    static void showToAll(JavaPlugin plugin, Entity target) {
        need(target);
        Rules r = existing(plugin);
        if (r == null) return;
        UUID t = target.getUUID();
        change(NetPort.server().getPlayerList().getPlayers(), target, () -> {
            r.fromAll.remove(t);
            Rules.removeEverywhere(r.shown, t);
            Rules.removeEverywhere(r.hidden, t);
        });
    }

    /** Disable hook: shows everything the plugin hid. */
    static void remove(JavaPlugin plugin) {
        Rules r = BY_PLUGIN.get(plugin);
        if (r == null) return;
        MinecraftServer server = NetPort.serverOrNull();
        List<Entity> targets = new ArrayList<Entity>();
        if (server != null) {
            for (UUID id : r.targets()) {
                Entity e = find(server, id);
                if (e != null) targets.add(e);
            }
        }
        List<ServerPlayer> viewers = server == null ? List.of() : new ArrayList<>(server.getPlayerList().getPlayers());
        boolean[] before = seen(viewers, targets);
        BY_PLUGIN.remove(plugin);
        publish();
        apply(viewers, targets, before);
    }

    /** For showing, which only undoes: allowed from onDisable too, and a no-op once the plugin's hides are gone. */
    private static Rules existing(JavaPlugin plugin) {
        NetPort.requireServerThread("Visibility");
        if (plugin == null) throw new IllegalArgumentException("Visibility needs the plugin doing the hiding; pass this");
        return BY_PLUGIN.get(plugin);
    }

    private static Rules rules(JavaPlugin plugin, String what) {
        NetPort.requireServerThread("Visibility");
        if (plugin == null) throw new IllegalArgumentException("Visibility needs the plugin doing the hiding; pass this");
        if (!plugin.isEnabled()) {
            throw new IllegalStateException(plugin.getName() + " can't " + what + " while it isn't enabled");
        }
        Rules r = BY_PLUGIN.get(plugin);
        if (r == null) {
            r = new Rules(plugin);
            // Published by change() once it holds a hide; until then it would be pruned as empty.
            BY_PLUGIN.put(plugin, r);
        }
        return r;
    }

    private static void change(List<ServerPlayer> viewers, Entity target, Runnable edit) {
        List<ServerPlayer> copy = new ArrayList<ServerPlayer>(viewers);
        List<Entity> targets = List.of(target);
        boolean[] before = seen(copy, targets);
        edit.run();
        publish();
        apply(copy, targets, before);
    }

    private static boolean[] seen(List<ServerPlayer> viewers, List<Entity> targets) {
        boolean[] out = new boolean[viewers.size() * targets.size()];
        int i = 0;
        for (Entity t : targets) for (ServerPlayer v : viewers) out[i++] = canSee(v, t);
        return out;
    }

    private static void apply(List<ServerPlayer> viewers, List<Entity> targets, boolean[] before) {
        int i = 0;
        for (Entity t : targets) {
            for (ServerPlayer v : viewers) {
                boolean was = before[i++];
                boolean now = canSee(v, t);
                if (v == t || was == now || v.hasDisconnected()) continue;
                if (now) showTo(v, t);
                else hideFrom(v, t);
            }
        }
    }

    private static void publish() {
        // A plugin that shows everything it hid stops costing a check per packet.
        BY_PLUGIN.values().removeIf(Rules::empty);
        Rules[] next = BY_PLUGIN.values().toArray(new Rules[0]);
        boolean any = false;
        for (Rules r : next) any |= !r.empty();
        rules = next;
        active = any;
    }

    private static void hideFrom(ServerPlayer viewer, Entity target) {
        // Despawn first, then drop the tab entry: the client needs the entry while the player entity exists.
        NetPort.retrack(viewer, target);
        if (target instanceof ServerPlayer p) {
            viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(p.getUUID())));
        }
    }

    private static void showTo(ServerPlayer viewer, Entity target) {
        // The tab entry first: the client won't spawn a player it has no entry for.
        if (target instanceof ServerPlayer p && !p.hasDisconnected()) {
            viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(p)));
        }
        NetPort.retrack(viewer, target);
    }

    private static Entity find(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) return p;
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    // ---- What reaches a viewer ----

    /** Removes hidden players' tab entries, and drops packets naming a hidden entity outside its own tracking. */
    static Packet<?> filter(ServerPlayer viewer, Packet<?> packet) {
        if (!active) return packet;
        if (packet instanceof ClientboundPlayerInfoUpdatePacket info) return info(viewer, info);
        int id;
        if (packet instanceof ClientboundBlockDestructionPacket p) id = p.getId();
        else if (packet instanceof ClientboundTakeItemEntityPacket p) id = p.getPlayerId();
        else if (packet instanceof ClientboundSoundEntityPacket p) id = p.getId();
        else return packet;
        // Entity ids resolve only on the server thread, where vanilla sends these.
        if (!viewer.server.isSameThread()) return packet;
        Entity e = viewer.serverLevel().getEntity(id);
        return e != null && !canSee(viewer, e) ? null : packet;
    }

    private static Packet<?> info(ServerPlayer viewer, ClientboundPlayerInfoUpdatePacket packet) {
        UUID v = viewer.getUUID();
        List<ClientboundPlayerInfoUpdatePacket.Entry> kept = null;
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = packet.entries();
        for (int i = 0; i < entries.size(); i++) {
            ClientboundPlayerInfoUpdatePacket.Entry e = entries.get(i);
            boolean visible = canSee(v, e.profileId());
            if (!visible && kept == null) kept = new ArrayList<>(entries.subList(0, i));
            else if (visible && kept != null) kept.add(e);
        }
        if (kept == null) return packet;
        return kept.isEmpty() ? null : NetPort.infoPacket(packet.actions(), kept);
    }

    /** Command suggestions: drops the names of online players this viewer can't see. */
    static Collection<String> names(ServerPlayer viewer, Collection<String> names) {
        if (!active) return names;
        List<String> out = new ArrayList<String>(names.size());
        for (String name : names) {
            ServerPlayer p = viewer.server.getPlayerList().getPlayerByName(name);
            if (p == null || canSee(viewer, p)) out.add(name);
        }
        return out;
    }
}
