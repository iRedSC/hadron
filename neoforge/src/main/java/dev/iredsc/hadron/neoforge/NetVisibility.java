package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
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
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Who can't see what, per plugin, by UUID. Written on the server thread, read from any thread (the send hook runs on
 * whichever thread sends), so every set is concurrent and the plugin list is a copied array. While nothing is hidden,
 * every check is one volatile read.
 */
public final class NetVisibility {
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
    @StaticState(reason = "each plugin's hiding rules, dropped in its disable hook")
    private static final Map<JavaPlugin, Rules> BY_PLUGIN = new IdentityHashMap<JavaPlugin, Rules>();
    @StaticState(reason = "a snapshot of BY_PLUGIN for packet threads, rebuilt whenever it changes")
    private static volatile Rules[] rules = new Rules[0];
    @StaticState(reason = "whether any rule exists, recomputed whenever BY_PLUGIN changes")
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

    /** For {@code /list}: what a viewer is allowed to see. */
    public static boolean canSeePlayer(ServerPlayer viewer, ServerPlayer target) {
        return canSee(viewer, target);
    }

    static boolean canSee(ServerPlayer viewer, Entity target) {
        if (!active || viewer == target) return true;
        return canSee(viewer.getUUID(), target.getUUID());
    }

    /** Whether any plugin has hidden this player from everyone: the server list and {@code /list} leave them out. */
    public static boolean hiddenFromEveryone(UUID target) {
        if (!active) return false;
        for (Rules r : rules) {
            if (r.fromAll.contains(target)) return true;
        }
        return false;
    }

    /** How many online players are hidden from everyone. Safe from the network thread: it only reads. */
    static int hiddenOnlineCount() {
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (Rules r : rules) seen.addAll(r.fromAll);
        int count = 0;
        for (UUID id : seen) if (server.getPlayerList().getPlayer(id) != null) count++;
        return count;
    }

    /** Whether any plugin hides anyone from everyone, so the ping can skip its filtering. */
    public static boolean anyHiddenFromEveryone() {
        if (!active) return false;
        for (Rules r : rules) {
            if (!r.fromAll.isEmpty()) return true;
        }
        return false;
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
    private static @Nullable Rules existing(JavaPlugin plugin) {
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

    private static @Nullable Entity find(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) return p;
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    // ---- What reaches a viewer ----

    /**
     * The entity whose own sound is being broadcast right now (a hidden player's hurt sound, footsteps), set around
     * {@code Entity.playSound} and {@code Player.playSound} on the server thread. Vanilla sends those as positional
     * sounds that don't name their entity, so this is how a hidden entity's sounds stay hidden.
     */
    @StaticState(reason = "set around one entity's sound on the server thread and restored after it")
    private static @Nullable Entity soundSource;

    /** Runs an entity's own sound with it as the source, when anyone is hidden; otherwise just runs it. */
    public static void playingSound(Entity source, Runnable play) {
        if (!active || !(source.level() instanceof ServerLevel)) {
            play.run();
            return;
        }
        Entity previous = soundSource;
        soundSource = source;
        try {
            play.run();
        } finally {
            soundSource = previous;
        }
    }

    /**
     * Removes hidden players' tab entries, drops packets naming a hidden entity outside its own tracking, and takes a
     * hidden entity out of the packets of entities the viewer does see: riders, leash holders, damage sources and
     * projectile owners.
     */
    static @Nullable Packet<?> filter(ServerPlayer viewer, Packet<?> packet) {
        if (!active) return packet;
        if (packet instanceof ClientboundPlayerInfoUpdatePacket info) return info(viewer, info);
        if (packet instanceof ClientboundSetPassengersPacket
                || packet instanceof ClientboundSetEntityLinkPacket
                || packet instanceof ClientboundDamageEventPacket
                || packet instanceof ClientboundAddEntityPacket) {
            // Entity ids resolve only on the server thread, where vanilla sends these.
            return viewer.server.isSameThread() ? secondary(viewer, packet) : packet;
        }
        int id;
        if (packet instanceof ClientboundBlockDestructionPacket p) id = p.getId();
        else if (packet instanceof ClientboundTakeItemEntityPacket p) id = p.getPlayerId();
        else if (packet instanceof ClientboundSoundEntityPacket p) id = p.getId();
        else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket) {
            Entity source = soundSource;
            return source != null && viewer.server.isSameThread() && !canSee(viewer, source) ? null : packet;
        } else return packet;
        // Entity ids resolve only on the server thread, where vanilla sends these.
        if (!viewer.server.isSameThread()) return packet;
        Entity e = viewer.serverLevel().getEntity(id);
        return e != null && !canSee(viewer, e) ? null : packet;
    }

    /** Whether the viewer must not learn of the entity with this id: it exists in their world and is hidden. */
    private static boolean hiddenId(ServerPlayer viewer, int id) {
        Entity e = viewer.serverLevel().getEntity(id);
        return e != null && e != viewer && !canSee(viewer, e);
    }

    private static @Nullable Packet<?> secondary(ServerPlayer viewer, Packet<?> packet) {
        switch (packet) {
            case ClientboundSetPassengersPacket p -> {
                int[] riders = p.getPassengers();
                int kept = 0;
                for (int rider : riders) if (!hiddenId(viewer, rider)) kept++;
                if (kept == riders.length) return packet;
                int[] visible = new int[kept];
                int i = 0;
                for (int rider : riders) if (!hiddenId(viewer, rider)) visible[i++] = rider;
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    buf.writeVarInt(p.getVehicle());
                    buf.writeVarIntArray(visible);
                    return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
                } finally {
                    buf.release();
                }
            }
            case ClientboundSetEntityLinkPacket p -> {
                if (p.getDestId() == 0 || !hiddenId(viewer, p.getDestId())) return packet;
                Entity leashed = viewer.serverLevel().getEntity(p.getSourceId());
                // Shown as not leashed: the client never learns who holds it.
                return leashed == null ? null : new ClientboundSetEntityLinkPacket(leashed, null);
            }
            case ClientboundDamageEventPacket p -> {
                boolean cause = p.sourceCauseId() >= 0 && hiddenId(viewer, p.sourceCauseId());
                boolean direct = p.sourceDirectId() >= 0 && hiddenId(viewer, p.sourceDirectId());
                if (!cause && !direct) return packet;
                return new ClientboundDamageEventPacket(p.entityId(), p.sourceType(), cause ? -1 : p.sourceCauseId(),
                        direct ? -1 : p.sourceDirectId(), p.sourcePosition());
            }
            case ClientboundAddEntityPacket p -> {
                if (p.getData() == 0 || !(viewer.serverLevel().getEntity(p.getId()) instanceof Projectile shot)) {
                    return packet;
                }
                Entity owner = shot.getOwner();
                if (owner == null || owner.getId() != p.getData() || !hiddenId(viewer, owner.getId())) return packet;
                return withoutOwner(viewer, p);
            }
            default -> {
                return packet;
            }
        }
    }

    /** The same spawn with no owner id (vanilla's 0), copied field by field in the packet's own wire order. */
    private static ClientboundAddEntityPacket withoutOwner(ServerPlayer viewer, ClientboundAddEntityPacket packet) {
        RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.buffer(), viewer.registryAccess());
        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), viewer.registryAccess());
        try {
            ClientboundAddEntityPacket.STREAM_CODEC.encode(in, packet);
            out.writeVarInt(in.readVarInt());
            out.writeUUID(in.readUUID());
            ByteBufCodecs.registry(Registries.ENTITY_TYPE).encode(out, ByteBufCodecs.registry(Registries.ENTITY_TYPE)
                    .decode(in));
            out.writeBytes(in, 3 * Double.BYTES + 3);
            in.readVarInt();
            out.writeVarInt(0);
            out.writeBytes(in, in.readableBytes());
            return ClientboundAddEntityPacket.STREAM_CODEC.decode(out);
        } finally {
            in.release();
            out.release();
        }
    }

    private static @Nullable Packet<?> info(ServerPlayer viewer, ClientboundPlayerInfoUpdatePacket packet) {
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
