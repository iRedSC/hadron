package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.EntityHandling;
import dev.iredsc.hadron.neoforge.LeashDrop;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A trader's llama despawning drops its lead a moment before it leaves the world, while it still looks present; this says why. */
@Mixin(TraderLlama.class)
abstract class TraderLlamaLeashMixin {
    @WrapOperation(method = "maybeDespawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/animal/horse/TraderLlama;dropLeash(ZZ)V"))
    private void hadron$leaving(TraderLlama self, boolean broadcast, boolean dropLead, Operation<Void> original) {
        EntityHandling.dropLeashBecause(self, LeashDrop.LEAVING, () -> original.call(self, broadcast, dropLead));
    }
}
