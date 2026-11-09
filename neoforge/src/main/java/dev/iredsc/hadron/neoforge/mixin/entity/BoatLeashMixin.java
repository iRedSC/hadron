package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A boat being destroyed drops its lead a moment before it leaves the world, while it still looks present; this says why. */
@Mixin(Boat.class)
abstract class BoatLeashMixin {
    @WrapOperation(method = "remove", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/Boat;dropLeash(ZZ)V"))
    private void hadron$leaving(Boat self, boolean broadcast, boolean dropLead, Operation<Void> original) {
        EntityHandling.dropLeashLeaving(self, () -> original.call(self, broadcast, dropLead));
    }
}
