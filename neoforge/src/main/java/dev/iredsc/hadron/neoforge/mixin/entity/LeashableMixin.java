package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leads coming off. Nearly every one goes through {@code dropLeash}: a player's click, a lead stretched too far, a dead
 * holder, mounting, a portal. The one that doesn't: an entity back from a save or a cross-dimension teleport whose
 * holder never shows up, which clears its lead directly. Only reported here; refusing is asked where a player acts
 * ({@code EntityHandling}).
 */
@Mixin(Leashable.class)
interface LeashableMixin {
    @WrapMethod(method = "dropLeash(Lnet/minecraft/world/entity/Entity;ZZ)V")
    private static void hadron$dropLeash(Entity entity, boolean broadcast, boolean dropLead,
            Operation<Void> original) {
        EntityHandling.dropLeash(entity, () -> original.call(entity, broadcast, dropLead));
    }

    /** After vanilla gives up on the saved holder: the lead has dropped and the leash data is cleared. */
    @Inject(method = "restoreLeashFromSave", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/entity/Leashable;setLeashData(Lnet/minecraft/world/entity/Leashable$LeashData;)V"))
    private static void hadron$holderNotFound(Entity entity, Leashable.LeashData data, CallbackInfo ci) {
        EntityHandling.leashHolderNotFound(entity);
    }
}
