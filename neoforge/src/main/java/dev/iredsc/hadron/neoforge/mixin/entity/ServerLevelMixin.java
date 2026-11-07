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
        long scope = Actors.Port.enter(EntityActors.of(entity));
        try {
            original.call(entity);
        } finally {
            Actors.Port.exit(scope);
        }
    }

    @WrapMethod(method = "tickPassenger")
    private void hadron$tickPassenger(Entity vehicle, Entity passenger, Operation<Void> original) {
        EntityMoves.beforeTick(passenger);
        long scope = Actors.Port.enter(EntityActors.of(passenger));
        try {
            original.call(vehicle, passenger);
        } finally {
            Actors.Port.exit(scope);
        }
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void hadron$spawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!EntitySpawns.add((ServerLevel) (Object) this, entity)) cir.setReturnValue(false);
    }
}
