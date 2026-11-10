package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.VehicleHooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A TNT minecart's hit, asked before a burning arrow sets it off; refused, it neither explodes nor wears. */
@Mixin(MinecartTNT.class)
abstract class MinecartTNTMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void hadron$hurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (VehicleHooks.refuseHit((MinecartTNT) (Object) this, source, amount)) cir.setReturnValue(false);
    }
}
