package dev.iredsc.hadron.neoforge.mixin.data;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.DataEffectCauses;
import dev.iredsc.hadron.neoforge.Lasting;
import dev.iredsc.hadron.neoforge.LastingCause;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Each effect keeps who applied it and where ({@link LastingCause}), hidden effects included; puts it on the actor
 * stack while the effect acts; and saves it with the entity, beside vanilla's own fields, so an entity saved without
 * it loads as before.
 */
@Mixin(MobEffectInstance.class)
public abstract class MobEffectInstanceMixin implements Lasting.EffectState {
    @Shadow
    private int duration;

    @Shadow
    private int amplifier;

    @Shadow
    private @Nullable MobEffectInstance hiddenEffect;

    @Unique
    private @Nullable LastingCause hadron$cause;

    @Override
    public @Nullable LastingCause hadron$cause() {
        return hadron$cause;
    }

    @Override
    public void hadron$setCause(@Nullable LastingCause cause) {
        hadron$cause = cause;
    }

    @Override
    public @Nullable MobEffectInstance hadron$hidden() {
        return hiddenEffect;
    }

    /** A copy keeps its source's cause, and a hidden effect resuming brings its own back. */
    @Inject(method = "setDetailsFrom", at = @At("TAIL"))
    private void hadron$copyCause(MobEffectInstance from, CallbackInfo ci) {
        hadron$cause = Lasting.cause(from);
    }

    /**
     * The credit moves only when the new application replaces this one: a higher amplifier, or the same one for
     * longer. A weaker or shorter one goes into the hidden effects with its own cause, or changes nothing.
     */
    @WrapMethod(method = "update")
    private boolean hadron$update(MobEffectInstance other, Operation<Boolean> original) {
        int amplifierBefore = amplifier;
        int durationBefore = duration;
        boolean changed = original.call(other);
        if (amplifier > amplifierBefore || (amplifier == amplifierBefore && duration != durationBefore)) {
            hadron$cause = Lasting.cause(other);
        }
        return changed;
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/effect/MobEffect;"
            + "applyEffectTick(Lnet/minecraft/world/entity/LivingEntity;I)Z"))
    private boolean hadron$act(MobEffect effect, LivingEntity entity, int level, Operation<Boolean> original) {
        if (entity.level().isClientSide()) return original.call(effect, entity, level);
        Lasting.Acting outer = Lasting.acting(entity);
        long scope = Lasting.enter(entity, hadron$cause);
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
        long scope = Lasting.enter(entity, hadron$cause);
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
        long scope = Lasting.enter(entity, hadron$cause);
        try {
            original.call(entity, source, amount);
        } finally {
            Lasting.exit(entity, outer, scope);
        }
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void hadron$save(CallbackInfoReturnable<Tag> cir) {
        if (cir.getReturnValue() instanceof CompoundTag tag) DataEffectCauses.save((MobEffectInstance) (Object) this, tag);
    }

    @Inject(method = "load", at = @At("RETURN"))
    private static void hadron$load(CompoundTag tag, CallbackInfoReturnable<@Nullable MobEffectInstance> cir) {
        MobEffectInstance loaded = cir.getReturnValue();
        if (loaded != null) DataEffectCauses.load(loaded, tag);
    }
}
