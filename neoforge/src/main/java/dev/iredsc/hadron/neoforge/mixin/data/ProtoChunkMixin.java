package dev.iredsc.hadron.neoforge.mixin.data;

import dev.iredsc.hadron.neoforge.EntitySpawns;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An entity generated with the world (a village's villagers, a new chunk's animals) is saved into its chunk here, on a
 * generation thread, and joins the world later as a loaded entity, never as a fresh one. So where it enters the world
 * is recorded here, just before it is saved; nothing else about it is: no event fires for generation.
 */
@Mixin(ProtoChunk.class)
abstract class ProtoChunkMixin {
    @Inject(method = "addEntity(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void hadron$generated(Entity entity, CallbackInfo ci) {
        EntitySpawns.generated(entity);
    }
}
