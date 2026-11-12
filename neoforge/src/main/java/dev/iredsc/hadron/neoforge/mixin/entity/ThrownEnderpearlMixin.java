package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.EntityTeleports;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla takes a pearl's thrower (a player, or any entity owning one) off what it rides before asking about the
 * teleport; it waits until the teleport goes ahead, so a refused pearl leaves the thrower riding.
 */
@Mixin(ThrownEnderpearl.class)
abstract class ThrownEnderpearlMixin {
    @WrapMethod(method = "onHit")
    private void hadron$onHit(HitResult result, Operation<Void> original) {
        EntityTeleports.leavingOnLanding(() -> {
            original.call(result);
            return null;
        });
    }

    @WrapOperation(method = "onHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;unRide()V"))
    private void hadron$unRideOnLanding(Entity owner, Operation<Void> original) {
        // A player whose connection is closing isn't teleported at all, and vanilla still takes them off.
        boolean teleports = !(owner instanceof ServerPlayer player) || player.connection.isAcceptingMessages();
        if (!teleports || !EntityTeleports.leaveOnLanding(owner, () -> original.call(owner))) {
            original.call(owner);
        }
    }
}
