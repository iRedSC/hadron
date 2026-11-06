package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityStatusChangeEvent;
import dev.iredsc.hadron.neoforge.EntityActors;
import dev.iredsc.hadron.neoforge.EntitySpawns;
import dev.iredsc.hadron.neoforge.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
abstract class MobMixin {
    /** AI turned off or on, for a mob already in the world (not a new one copying its parent's flag). */
    @Inject(method = "setNoAi", at = @At("HEAD"), cancellable = true)
    private void hadron$noAi(boolean noAi, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (!(self.level() instanceof ServerLevel) || !self.isAddedToLevel() || self.isNoAi() == noAi
                || !Hooks.listens(EntityStatusChangeEvent.class)) return;
        if (Hooks.post(new EntityStatusChangeEvent(self, EntityStatusChangeEvent.NO_AI, noAi,
                EntityActors.fromStack(self, Actor.Unknown.INSTANCE))).isCancelled()) {
            ci.cancel();
        }
    }

    /**
     * The new mob of a conversion is not a spawn (refusing it would delete the old mob with nothing in its place),
     * and it carries the old mob's attribution.
     */
    @WrapMethod(method = "convertTo")
    private <T extends Mob> T hadron$convertTo(EntityType<T> type, boolean transferInventory,
            Operation<T> original) {
        Mob self = (Mob) (Object) this;
        if (!(self.level() instanceof ServerLevel)) return original.call(type, transferInventory);
        return EntitySpawns.converting(self, () -> original.call(type, transferInventory));
    }
}
