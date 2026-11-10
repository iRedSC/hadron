package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.VehicleHooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Any hit shatters an end crystal, removing it and setting off its blast, without damage NeoForge would see. Asked
 * as a removal just before; refused, the crystal stays and nothing explodes.
 */
@Mixin(EndCrystal.class)
abstract class EndCrystalMixin {
    @Inject(method = "hurt", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/boss/enderdragon/EndCrystal;remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void hadron$shatter(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (VehicleHooks.refuseShatter((EndCrystal) (Object) this, source)) cir.setReturnValue(false);
    }
}
