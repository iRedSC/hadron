package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.net.NetBackend;
import dev.iredsc.hadron.api.net.PacketHandler;
import dev.iredsc.hadron.api.net.PacketRegistration;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.common.PluginHost;
import dev.iredsc.hadron.neoforge.mixin.net.ChunkMapAccessor;
import dev.iredsc.hadron.neoforge.mixin.net.PlayerInfoUpdatePacketAccessor;
import dev.iredsc.hadron.neoforge.mixin.net.TrackedEntityAccessor;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** The NeoForge side of packet interception, per-player visibility and display names. Mixins in {@code mixin.net}. */
final class NetPort implements NetBackend {
    private NetPort() {}

    static void register() {
        NetBackend.Port.install(new NetPort());
        // Lowest, so the name a plugin asked for is the one shown, whatever mods add earlier.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, NetNames::nameFormat);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, NetNames::tabListNameFormat);
        PluginHost.beforePluginDisable(NetPackets::awaitRunning);
        PluginHost.onPluginDisable(plugin -> {
            NetPackets.remove(plugin);
            NetVisibility.remove(plugin);
            NetNames.remove(plugin);
        });
    }

    // ---- NetBackend ----

    @Override
    public PacketRegistration listen(JavaPlugin plugin, Class<? extends Packet<?>> type, EventPriority priority,
            PacketHandler<?> handler, boolean outgoing) {
        return NetPackets.listen(plugin, type, priority, handler, outgoing);
    }

    @Override
    public void hide(JavaPlugin plugin, ServerPlayer viewer, Entity target) {
        NetVisibility.hide(plugin, requireViewer(viewer), target);
    }

    @Override
    public void show(JavaPlugin plugin, ServerPlayer viewer, Entity target) {
        NetVisibility.show(plugin, requireViewer(viewer), target);
    }

    @Override
    public void hideFromAll(JavaPlugin plugin, Entity target) {
        NetVisibility.hideFromAll(plugin, target);
    }

    @Override
    public void showToAll(JavaPlugin plugin, Entity target) {
        NetVisibility.showToAll(plugin, target);
    }

    @Override
    public boolean canSee(ServerPlayer viewer, Entity target) {
        return NetVisibility.canSee(requireViewer(viewer), java.util.Objects.requireNonNull(target, "target"));
    }

    @Override
    public boolean isHiddenFromAll(JavaPlugin plugin, Entity target) {
        return NetVisibility.isHiddenFromAll(plugin, target);
    }

    @Override
    public void setDisplayName(JavaPlugin plugin, ServerPlayer player, Component name) {
        NetNames.setDisplayName(plugin, player, name);
    }

    @Override
    public void setPlayerListName(JavaPlugin plugin, ServerPlayer player, Component name) {
        NetNames.setPlayerListName(plugin, player, name);
    }

    @Override
    public void setNameTag(JavaPlugin plugin, ServerPlayer player, String tag) {
        NetNames.setNameTag(plugin, player, tag);
    }

    private static ServerPlayer requireViewer(ServerPlayer viewer) {
        if (viewer == null) throw new IllegalArgumentException("Visibility needs the viewer; it was null");
        return viewer;
    }

    // ---- Shared helpers ----

    static MinecraftServer serverOrNull() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    static MinecraftServer server() {
        MinecraftServer s = serverOrNull();
        if (s == null) throw new IllegalStateException("No server is running");
        return s;
    }

    static void requireServerThread(String api) {
        if (!server().isSameThread()) {
            throw new IllegalStateException(api + " changes run on the server thread. From a packet handler, use "
                    + "event.runOnServerThread; elsewhere, getServer().getScheduler().runTask");
        }
    }

    /** Re-asks the viewer's tracking whether to show the target, which spawns or despawns it on their client. */
    static void retrack(ServerPlayer viewer, Entity target) {
        TrackedEntityAccessor tracked = tracked(viewer, target);
        if (tracked != null) tracked.hadron$updatePlayer(viewer);
    }

    /** Despawns the target on the viewer's client, if it was spawned there. */
    static void untrack(ServerPlayer viewer, Entity target) {
        TrackedEntityAccessor tracked = tracked(viewer, target);
        if (tracked != null) tracked.hadron$removePlayer(viewer);
    }

    private static TrackedEntityAccessor tracked(ServerPlayer viewer, Entity target) {
        if (target.isRemoved() || viewer.level() != target.level() || !(target.level() instanceof ServerLevel level)) {
            return null;
        }
        Object tracked = ((ChunkMapAccessor) level.getChunkSource().chunkMap).hadron$entityMap().get(target.getId());
        return (TrackedEntityAccessor) tracked;
    }

    /** A tab list packet with these entries; vanilla only builds one from online players. */
    static ClientboundPlayerInfoUpdatePacket infoPacket(EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions,
            List<ClientboundPlayerInfoUpdatePacket.Entry> entries) {
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(EnumSet.copyOf(actions), List.of());
        ((PlayerInfoUpdatePacketAccessor) packet).hadron$setEntries(List.copyOf(entries));
        return packet;
    }
}
