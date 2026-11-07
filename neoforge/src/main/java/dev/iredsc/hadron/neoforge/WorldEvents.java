package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.world.ChunkLoadEvent;
import dev.iredsc.hadron.api.event.world.ChunkUnloadEvent;
import dev.iredsc.hadron.api.event.world.ExplosionPrimeEvent;
import dev.iredsc.hadron.api.event.world.LightningStrikeEvent;
import dev.iredsc.hadron.api.event.world.WorldLoadEvent;
import dev.iredsc.hadron.api.event.world.WorldSaveEvent;
import dev.iredsc.hadron.api.event.world.WorldUnloadEvent;
import dev.iredsc.hadron.neoforge.mixin.world.ExplosionAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Hadron's world, resource, player and server events ({@code dev.iredsc.hadron.api.event.world},
 * {@code ...event.resource}, {@code ...event.player}, {@code ...event.server}) from NeoForge's events and the world
 * mixins ({@code mixin.world}, through {@link WorldHooks}). Player and server listeners are in {@link PlayerEvents}
 * and {@link ServerEvents}.
 */
final class WorldEvents {
    private WorldEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(WorldEvents::explosionStart);
        NeoForge.EVENT_BUS.addListener(WorldEvents::explosionDetonate);
        NeoForge.EVENT_BUS.addListener(WorldEvents::lightning);
        NeoForge.EVENT_BUS.addListener(WorldEvents::chunkLoad);
        NeoForge.EVENT_BUS.addListener(WorldEvents::chunkUnload);
        NeoForge.EVENT_BUS.addListener(WorldEvents::levelLoad);
        NeoForge.EVENT_BUS.addListener(WorldEvents::levelUnload);
        NeoForge.EVENT_BUS.addListener(WorldEvents::levelSave);
        PlayerEvents.register();
        ServerEvents.register();
    }

    /** Level.explode, after the explosion is built and before it touches anything. */
    private static void explosionStart(ExplosionEvent.Start event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Hooks.listens(ExplosionPrimeEvent.class)) return;
        Explosion explosion = event.getExplosion();
        ExplosionAccessor access = (ExplosionAccessor) explosion;
        float radius = explosion.radius();
        boolean fire = access.hadron$fire();
        ExplosionPrimeEvent hadron = Hooks.post(new ExplosionPrimeEvent(
                WorldActors.of(explosion.getDirectSourceEntity()), level, explosion, radius, fire));
        if (hadron.isCancelled()) {
            event.setCanceled(true);
            return;
        }
        if (hadron.getRadius() != radius) access.hadron$setRadius(hadron.getRadius());
        if (hadron.getFire() != fire) access.hadron$setFire(hadron.getFire());
    }

    /** Explosion.explode, for every explosion however it was started; the lists are what it will destroy and hit. */
    private static void explosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !Hooks.listens(dev.iredsc.hadron.api.event.world.ExplosionEvent.class)) return;
        Explosion explosion = event.getExplosion();
        var hadron = Hooks.post(new dev.iredsc.hadron.api.event.world.ExplosionEvent(
                WorldActors.of(explosion.getDirectSourceEntity()), level, explosion, event.getAffectedBlocks(),
                event.getAffectedEntities()));
        if (hadron.isCancelled()) {
            event.getAffectedBlocks().clear();
            event.getAffectedEntities().clear();
        }
    }

    /** Every bolt added to a world, before it does anything. */
    private static void lightning(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof LightningBolt bolt)
                || !(event.getLevel() instanceof ServerLevel level) || !Hooks.listens(LightningStrikeEvent.class)) {
            return;
        }
        var actor = bolt.getCause() != null ? WorldActors.player(bolt.getCause()) : WorldActors.orNature();
        if (Hooks.post(new LightningStrikeEvent(actor, level, bolt)).isCancelled()) event.setCanceled(true);
    }

    private static void chunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk
                && Hooks.listens(ChunkLoadEvent.class)) {
            Hooks.post(new ChunkLoadEvent(WorldActors.orNature(), level, chunk, event.isNewChunk()));
        }
    }

    private static void chunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk
                && Hooks.listens(ChunkUnloadEvent.class)) {
            Hooks.post(new ChunkUnloadEvent(WorldActors.orNature(), level, chunk));
        }
    }

    private static void levelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && Hooks.listens(WorldLoadEvent.class)) {
            Hooks.post(new WorldLoadEvent(level));
        }
    }

    private static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && Hooks.listens(WorldUnloadEvent.class)) {
            Hooks.post(new WorldUnloadEvent(level));
        }
    }

    private static void levelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && Hooks.listens(WorldSaveEvent.class)) {
            Hooks.post(new WorldSaveEvent(level));
        }
    }
}
