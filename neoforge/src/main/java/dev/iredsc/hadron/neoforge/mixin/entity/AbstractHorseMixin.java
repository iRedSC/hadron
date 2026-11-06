package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A tamed horse, donkey or llama answers to its owner. */
@Mixin(AbstractHorse.class)
abstract class AbstractHorseMixin {
    @Inject(method = "setOwnerUUID", at = @At("TAIL"))
    private void hadron$owner(UUID owner, CallbackInfo ci) {
        AbstractHorse self = (AbstractHorse) (Object) this;
        if (owner != null && self.level() instanceof ServerLevel) Actors.remember(self, Attribution.of(owner));
    }
}
