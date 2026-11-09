package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.EntityHandling;
import dev.iredsc.hadron.neoforge.LeashDrop;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Putting a lead on a mob or boat, and taking one off: vanilla does both in {@code Entity.interact}, which mobs and
 * boats reach through {@code super}, after their own name tag and spawn egg handling. Refused, the click fails, so no
 * advancement counts it, and {@link PlayerInteractMixin} doesn't hand it on to the held item (shearing the sheep
 * instead).
 */
@Mixin(Entity.class)
abstract class EntityLeashMixin {
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void hadron$leash(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (EntityHandling.refuseLeashClick((Entity) (Object) this, player, hand)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    /** The player's own unleash, already asked as an EntityUnleashEvent: never also a break, wherever the mob is now. */
    @WrapOperation(method = "interact", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Leashable;dropLeash(ZZ)V"))
    private void hadron$chosenUnleash(Leashable leashable, boolean broadcast, boolean dropLead,
            Operation<Void> original) {
        EntityHandling.dropLeashBecause((Entity) (Object) this, LeashDrop.CHOSEN,
                () -> original.call(leashable, broadcast, dropLead));
    }
}
