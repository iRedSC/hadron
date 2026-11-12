package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.DataEffectCauses;
import dev.iredsc.hadron.neoforge.Lasting;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While an effect acts (its tick, and what it does as its entity is hurt or goes), whoever applied it is on the actor
 * stack, worked out only if something reads it ({@link Lasting#enter}); and its chain may change in a tick only as a
 * tick changes it, a hidden effect resuming with its own cause ({@link DataEffectCauses#afterTick}).
 */
@Mixin(MobEffectInstance.class)
abstract class MobEffectInstanceMixin {
    /** A tick may change the chain only as a tick does; anything else since the last look is lost first. */
    @WrapMethod(method = "tick")
    private boolean hadron$tick(LivingEntity entity, Runnable onUpdate, Operation<Boolean> original) {
        if (entity.level().isClientSide()) return original.call(entity, onUpdate);
        MobEffectInstance self = (MobEffectInstance) (Object) this;
        DataEffectCauses.beforeTick(entity, self);
        boolean going = original.call(entity, onUpdate);
        DataEffectCauses.afterTick(entity, self);
        return going;
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/effect/MobEffect;"
            + "applyEffectTick(Lnet/minecraft/world/entity/LivingEntity;I)Z"))
    private boolean hadron$act(MobEffect effect, LivingEntity entity, int level, Operation<Boolean> original) {
        if (entity.level().isClientSide()) return original.call(effect, entity, level);
        Lasting.Acting outer = Lasting.acting(entity);
        long scope = Lasting.enter(entity, DataEffectCauses.active(entity, (MobEffectInstance) (Object) this));
        try {
            return original.call(effect, entity, level);
        } finally {
            Lasting.exit(entity, outer, scope);
        }
    }

    /** What an effect does as its entity goes (oozing's slimes, weaving's cobwebs) is its cause's. */
    @WrapMethod(method = "onMobRemoved")
    private void hadron$removed(LivingEntity entity, Entity.RemovalReason reason, Operation<Void> original) {
        if (entity.level().isClientSide()) {
            original.call(entity, reason);
            return;
        }
        Lasting.Acting outer = Lasting.acting(entity);
        long scope = Lasting.enter(entity, DataEffectCauses.active(entity, (MobEffectInstance) (Object) this));
        try {
            original.call(entity, reason);
        } finally {
            Lasting.exit(entity, outer, scope);
        }
    }

    /** What an effect does as its entity is hurt (infested's silverfish) is its cause's. */
    @WrapMethod(method = "onMobHurt")
    private void hadron$hurt(LivingEntity entity, DamageSource source, float amount, Operation<Void> original) {
        if (entity.level().isClientSide()) {
            original.call(entity, source, amount);
            return;
        }
        Lasting.Acting outer = Lasting.acting(entity);
        long scope = Lasting.enter(entity, DataEffectCauses.active(entity, (MobEffectInstance) (Object) this));
        try {
            original.call(entity, source, amount);
        } finally {
            Lasting.exit(entity, outer, scope);
        }
    }
}
