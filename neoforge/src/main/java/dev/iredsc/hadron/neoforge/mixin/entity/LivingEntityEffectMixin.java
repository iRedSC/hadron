package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.Lasting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every effect applied to a living entity records who applied it and where, before vanilla decides what it replaces. */
@Mixin(LivingEntity.class)
abstract class LivingEntityEffectMixin {
    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"))
    private void hadron$applied(MobEffectInstance effect, @Nullable Entity source,
            CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level() instanceof ServerLevel) Lasting.effectApplied(self, effect, source);
    }

    @Inject(method = "forceAddEffect", at = @At("HEAD"))
    private void hadron$forced(MobEffectInstance effect, @Nullable Entity source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level() instanceof ServerLevel) Lasting.effectApplied(self, effect, source);
    }
}
