package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.LastingActs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What is acting on each entity (an effect or its fire), and its fire's cause: an ignition that leaves more burning
 * time takes the credit, and burning acts for it.
 */
@Mixin(Entity.class)
abstract class EntityLastingMixin implements LastingActs.State {
    @Shadow
    private int remainingFireTicks;

    @Shadow
    public abstract Level level();

    @Unique
    private LastingActs.@Nullable Acting hadron$acting;

    @Override
    public LastingActs.@Nullable Acting hadron$acting() {
        return hadron$acting;
    }

    @Override
    public void hadron$setActing(LastingActs.@Nullable Acting acting) {
        hadron$acting = acting;
    }

    @Unique
    private LastingActs.@Nullable FireUndo hadron$fireUndo;

    @Override
    public LastingActs.@Nullable FireUndo hadron$fireUndo() {
        return hadron$fireUndo;
    }

    @Override
    public void hadron$setFireUndo(LastingActs.@Nullable FireUndo undo) {
        hadron$fireUndo = undo;
    }

    @Unique
    private boolean hadron$burningDown;

    @Override
    public boolean hadron$burningDown() {
        return hadron$burningDown;
    }

    @Override
    public void hadron$setBurningDown(boolean burningDown) {
        hadron$burningDown = burningDown;
    }

    /** The fire burning down by itself each tick, so the credit hook never reads it as an ignition taken back. */
    @WrapOperation(method = "baseTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setRemainingFireTicks(I)V"))
    private void hadron$burnDown(Entity self, int ticks, Operation<Void> original) {
        boolean outer = hadron$burningDown;
        hadron$burningDown = true;
        try {
            original.call(self, ticks);
        } finally {
            hadron$burningDown = outer;
        }
    }

    @Inject(method = "setRemainingFireTicks", at = @At("HEAD"))
    private void hadron$fireCause(int ticks, CallbackInfo ci) {
        if (ticks != remainingFireTicks && !level().isClientSide()) {
            LastingActs.fireSet((Entity) (Object) this, remainingFireTicks, ticks);
        }
    }

    /**
     * An entity in the world read again from NBT ({@code /data}) has its burning time written past the setter: the
     * same change, with the same credit. One only now loading isn't in the world yet, and keeps the cause saved with it.
     */
    @WrapMethod(method = "load")
    private void hadron$fireLoaded(CompoundTag tag, Operation<Void> original) {
        int before = remainingFireTicks;
        original.call(tag);
        Entity self = (Entity) (Object) this;
        if (remainingFireTicks != before && self.isAddedToLevel() && !level().isClientSide()) {
            LastingActs.fireSet(self, before, remainingFireTicks);
        }
    }

    @WrapOperation(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;"
            + "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean hadron$burn(Entity self, DamageSource source, float amount, Operation<Boolean> original) {
        LastingActs.Acting outer = LastingActs.acting(self);
        long scope = LastingActs.enter(self, LastingActs.fireCause(self));
        try {
            return original.call(self, source, amount);
        } finally {
            LastingActs.exit(self, outer, scope);
        }
    }
}
