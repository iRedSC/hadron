package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockPlaceEvent;
import dev.iredsc.hadron.api.event.player.PlayerChatEvent;
import dev.iredsc.hadron.api.event.player.PlayerDeathEvent;
import dev.iredsc.hadron.api.event.player.PlayerJoinEvent;
import dev.iredsc.hadron.api.event.player.PlayerQuitEvent;
import dev.iredsc.hadron.api.event.server.ServerLoadEvent;
import dev.iredsc.hadron.common.PluginHost;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Fires Hadron's events from NeoForge's. Each Hadron event is posted from the NeoForge event that means the same
 * thing, and a cancel goes back the same way. This and the mod class are all a new loader port has to rewrite.
 */
final class NeoForgeEvents {
    private final Supplier<PluginHost> hosts;

    NeoForgeEvents(Supplier<PluginHost> hosts) {
        this.hosts = hosts;
    }

    void register() {
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::join);
        NeoForge.EVENT_BUS.addListener(this::quit);
        NeoForge.EVENT_BUS.addListener(this::chat);
        NeoForge.EVENT_BUS.addListener(this::death);
        NeoForge.EVENT_BUS.addListener(this::breakBlock);
        NeoForge.EVENT_BUS.addListener(this::placeBlock);
    }

    private void started(ServerStartedEvent event) {
        PluginHost host = hosts.get();
        if (host != null) host.post(new ServerLoadEvent(event.getServer()));
    }

    private void join(PlayerEvent.PlayerLoggedInEvent event) {
        PluginHost host = hosts.get();
        if (host != null && event.getEntity() instanceof ServerPlayer p) host.post(new PlayerJoinEvent(p));
    }

    private void quit(PlayerEvent.PlayerLoggedOutEvent event) {
        PluginHost host = hosts.get();
        if (host != null && event.getEntity() instanceof ServerPlayer p) host.post(new PlayerQuitEvent(p));
    }

    private void chat(ServerChatEvent event) {
        PluginHost host = hosts.get();
        if (host == null) return;
        PlayerChatEvent hadron = new PlayerChatEvent(event.getPlayer(), event.getRawText(), event.getMessage());
        host.post(hadron);
        if (hadron.isCancelled()) {
            event.setCanceled(true);
        } else if (hadron.getMessage() != event.getMessage()) {
            event.setMessage(hadron.getMessage());
        }
    }

    private void death(LivingDeathEvent event) {
        PluginHost host = hosts.get();
        if (host != null && event.getEntity() instanceof ServerPlayer p) {
            host.post(new PlayerDeathEvent(p, event.getSource()));
        }
    }

    private void breakBlock(BlockEvent.BreakEvent event) {
        PluginHost host = hosts.get();
        if (host == null || !(event.getPlayer() instanceof ServerPlayer p)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockBreakEvent hadron = new BlockBreakEvent(level, event.getPos(), event.getState(), p);
        host.post(hadron);
        if (hadron.isCancelled()) event.setCanceled(true);
    }

    private void placeBlock(BlockEvent.EntityPlaceEvent event) {
        PluginHost host = hosts.get();
        if (host == null || !(event.getEntity() instanceof ServerPlayer p)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPlaceEvent hadron = new BlockPlaceEvent(level, event.getPos(), event.getPlacedBlock(), p);
        host.post(hadron);
        if (hadron.isCancelled()) event.setCanceled(true);
    }
}
