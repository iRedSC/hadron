package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.Lasting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Every effect applied to a living entity records who applied it and where, on the effect vanilla really keeps:
 * the one it adds, the one it replaces, or the copy it hides underneath; an instance already active on another entity
 * is applied as a copy, so each keeps its own cause ({@link Lasting#applying}).
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityEffectMixin {
    @WrapMethod(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean hadron$applied(MobEffectInstance effect, @Nullable Entity source, Operation<Boolean> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel)) return original.call(effect, source);
        return Lasting.applying(self, effect, source, applied -> original.call(applied, source));
    }

    @WrapMethod(method = "forceAddEffect")
    private void hadron$forced(MobEffectInstance effect, @Nullable Entity source, Operation<Void> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self.level() instanceof ServerLevel)) {
            original.call(effect, source);
            return;
        }
        Lasting.applying(self, effect, source, applied -> {
            original.call(applied, source);
            return Boolean.TRUE;
        });
    }
}
