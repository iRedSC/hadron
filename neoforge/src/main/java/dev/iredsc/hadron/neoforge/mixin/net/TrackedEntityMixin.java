package dev.iredsc.hadron.neoforge.mixin.net;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.NetHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Entity tracking's one question, asked whenever an entity or player moves, spawns or changes world: should this
 * player's client have this entity? A hidden entity is never paired, so nothing about it is sent.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;broadcastToPlayer(Lnet/minecraft/server/level/ServerPlayer;)Z"))
    private boolean hadron$visible(Entity entity, ServerPlayer viewer, Operation<Boolean> original) {
        return original.call(entity, viewer) && NetHooks.canSee(viewer, entity);
    }
}
