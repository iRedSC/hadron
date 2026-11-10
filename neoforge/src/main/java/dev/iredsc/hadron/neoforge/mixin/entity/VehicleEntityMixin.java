package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.VehicleHooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hit on a boat or minecart, asked before it wobbles, wears or breaks. */
@Mixin(VehicleEntity.class)
abstract class VehicleEntityMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void hadron$hurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        VehicleEntity self = (VehicleEntity) (Object) this;
        // A TNT minecart asks in its own hurt, before a burning arrow sets it off, and then calls this one.
        if (self instanceof MinecartTNT) return;
        if (VehicleHooks.refuseHit(self, source, amount)) cir.setReturnValue(false);
    }

    /**
     * The amount the hit deals is what the event reported. Either order with the check above is right: the report of a
     * reported amount is itself. A TNT minecart's own hurt passes its amount here, so it is covered too.
     */
    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true)
    private float hadron$dealt(float amount) {
        return VehicleHooks.dealt(amount);
    }
}
