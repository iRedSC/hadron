package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.EntityActors;
import dev.iredsc.hadron.neoforge.EntityMoves;
import dev.iredsc.hadron.neoforge.EntitySpawns;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Each ticking entity is the actor while it ticks; every fresh entity passes the spawn event. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @WrapMethod(method = "tickNonPassenger")
    private void hadron$tickEntity(Entity entity, Operation<Void> original) {
        EntityMoves.beforeTick(entity);
        try (Actors.Scope scope = Actors.push(EntityActors.of(entity))) {
            original.call(entity);
        }
    }

    @WrapMethod(method = "tickPassenger")
    private void hadron$tickPassenger(Entity vehicle, Entity passenger, Operation<Void> original) {
        EntityMoves.beforeTick(passenger);
        try (Actors.Scope scope = Actors.push(EntityActors.of(passenger))) {
            original.call(vehicle, passenger);
        }
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void hadron$spawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!EntitySpawns.add((ServerLevel) (Object) this, entity)) cir.setReturnValue(false);
    }
}
