package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.Lasting;
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
abstract class EntityLastingMixin implements Lasting.State {
    @Shadow
    private int remainingFireTicks;

    @Shadow
    public abstract Level level();

    @Unique
    private Lasting.@Nullable Acting hadron$acting;

    @Override
    public Lasting.@Nullable Acting hadron$acting() {
        return hadron$acting;
    }

    @Override
    public void hadron$setActing(Lasting.@Nullable Acting acting) {
        hadron$acting = acting;
    }

    @Unique
    private Lasting.@Nullable FireUndo hadron$fireUndo;

    @Override
    public Lasting.@Nullable FireUndo hadron$fireUndo() {
        return hadron$fireUndo;
    }

    @Override
    public void hadron$setFireUndo(Lasting.@Nullable FireUndo undo) {
        hadron$fireUndo = undo;
    }

    @Inject(method = "setRemainingFireTicks", at = @At("HEAD"))
    private void hadron$fireCause(int ticks, CallbackInfo ci) {
        if (ticks != remainingFireTicks && !level().isClientSide()) {
            Lasting.fireSet((Entity) (Object) this, remainingFireTicks, ticks);
        }
    }

    @WrapOperation(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;"
            + "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean hadron$burn(Entity self, DamageSource source, float amount, Operation<Boolean> original) {
        Lasting.Acting outer = Lasting.acting(self);
        long scope = Lasting.enter(self, Lasting.fireCause(self));
        try {
            return original.call(self, source, amount);
        } finally {
            Lasting.exit(self, outer, scope);
        }
    }
}
