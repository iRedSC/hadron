package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
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
}
