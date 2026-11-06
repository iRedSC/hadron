package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.player.PlayerDeathEvent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/** Hadron's entity events ({@code dev.iredsc.hadron.api.event.entity}) from NeoForge's events and the entity mixins. */
final class EntityEvents {
    private EntityEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(EntityEvents::death);
    }

    private static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && Hooks.listens(PlayerDeathEvent.class)) {
            Hooks.post(new PlayerDeathEvent(p, event.getSource()));
        }
    }
}
