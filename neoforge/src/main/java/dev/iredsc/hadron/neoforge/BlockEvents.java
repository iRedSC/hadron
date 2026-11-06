package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockPlaceEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Hadron's block events ({@code dev.iredsc.hadron.api.event.block}) from NeoForge's events and the block mixins. */
final class BlockEvents {
    private BlockEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(BlockEvents::breakBlock);
        NeoForge.EVENT_BUS.addListener(BlockEvents::placeBlock);
    }

    private static void breakBlock(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer p) || !(event.getLevel() instanceof ServerLevel level)
                || !Hooks.listens(BlockBreakEvent.class)) return;
        BlockBreakEvent hadron = Hooks.post(new BlockBreakEvent(level, event.getPos(), event.getState(),
                level.getFluidState(event.getPos()).createLegacyBlock(), new Actor.Player(p)));
        if (hadron.isCancelled()) event.setCanceled(true);
    }

    private static void placeBlock(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getLevel() instanceof ServerLevel level)
                || !Hooks.listens(BlockPlaceEvent.class)) return;
        Actor actor = new Actor.Player(p);
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (var snapshot : multi.getReplacedBlockSnapshots()) {
                BlockPlaceEvent hadron = Hooks.post(new BlockPlaceEvent(level, snapshot.getPos(),
                        snapshot.getState(), snapshot.getCurrentState(), actor));
                if (hadron.isCancelled()) event.setCanceled(true);
            }
            return;
        }
        BlockPlaceEvent hadron = Hooks.post(new BlockPlaceEvent(level, event.getPos(),
                event.getBlockSnapshot().getState(), event.getPlacedBlock(), actor));
        if (hadron.isCancelled()) event.setCanceled(true);
    }

}
