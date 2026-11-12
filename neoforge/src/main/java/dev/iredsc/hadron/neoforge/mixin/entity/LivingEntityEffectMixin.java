package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.DataEffectCauses;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Who applied each effect, kept on the entity as its chain changes ({@link DataEffectCauses}): recorded where an
 * effect is applied, before its first tick, and dropped when it ends.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityEffectMixin {
    @WrapMethod(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean hadron$applied(MobEffectInstance effect, @Nullable Entity source, Operation<Boolean> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel)) return original.call(effect, source);
        return DataEffectCauses.applying(self, effect, source, () -> original.call(effect, source));
    }

    @WrapMethod(method = "forceAddEffect")
    private void hadron$forced(MobEffectInstance effect, @Nullable Entity source, Operation<Void> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel)) {
            original.call(effect, source);
            return;
        }
        DataEffectCauses.applying(self, effect, source, () -> {
            original.call(effect, source);
            return Boolean.TRUE;
        });
    }

    /** Ran out, cured (milk, a totem) or cleared: every path that ends an effect tells the entity here. */
    @Inject(method = "onEffectRemoved", at = @At("HEAD"))
    private void hadron$ended(MobEffectInstance effect, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level() instanceof ServerLevel) DataEffectCauses.removed(self, effect.getEffect());
    }

    /** Taken off without telling the entity, as some mods do. */
    @Inject(method = "removeEffectNoUpdate", at = @At("RETURN"))
    private void hadron$takenOff(Holder<MobEffect> effect, CallbackInfoReturnable<@Nullable MobEffectInstance> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (cir.getReturnValue() != null && self.level() instanceof ServerLevel) DataEffectCauses.removed(self, effect);
    }
}
