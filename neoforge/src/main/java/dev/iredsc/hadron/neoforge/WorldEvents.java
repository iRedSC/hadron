package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.player.PlayerChatEvent;
import dev.iredsc.hadron.api.event.player.PlayerJoinEvent;
import dev.iredsc.hadron.api.event.player.PlayerQuitEvent;
import dev.iredsc.hadron.api.event.server.ServerLoadEvent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Hadron's world, resource, player and server events ({@code dev.iredsc.hadron.api.event.world},
 * {@code ...event.resource}, {@code ...event.player}, {@code ...event.server}) from NeoForge's events and the world
 * mixins.
 */
final class WorldEvents {
    private WorldEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(WorldEvents::started);
        NeoForge.EVENT_BUS.addListener(WorldEvents::join);
        NeoForge.EVENT_BUS.addListener(WorldEvents::quit);
        NeoForge.EVENT_BUS.addListener(WorldEvents::chat);
    }

    private static void started(ServerStartedEvent event) {
        Hooks.post(new ServerLoadEvent(event.getServer()));
    }

    private static void join(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) Hooks.post(new PlayerJoinEvent(p));
    }

    private static void quit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) Hooks.post(new PlayerQuitEvent(p));
    }

    private static void chat(ServerChatEvent event) {
        if (!Hooks.listens(PlayerChatEvent.class)) return;
        PlayerChatEvent hadron = Hooks.post(new PlayerChatEvent(event.getPlayer(), event.getRawText(),
                event.getMessage()));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
        } else if (hadron.getMessage() != event.getMessage()) {
            event.setMessage(hadron.getMessage());
        }
    }
}
