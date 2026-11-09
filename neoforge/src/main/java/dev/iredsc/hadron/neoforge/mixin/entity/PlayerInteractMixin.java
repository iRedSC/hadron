package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.EntityState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A click on an entity that a plugin refused inside {@code Entity.interact} ends there, failed: vanilla would
 * otherwise try the held item next, so a refused unleash with shears in hand would shear the sheep.
 */
@Mixin(Player.class)
abstract class PlayerInteractMixin {
    private static final String INTERACT =
            "Lnet/minecraft/world/entity/Entity;interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;";

    @Inject(method = "interactOn", at = @At(value = "INVOKE", target = INTERACT))
    private void hadron$clear(Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        ((EntityState) this).hadron$setInteractRefused(false);
    }

    @Inject(method = "interactOn", cancellable = true, at = @At(value = "INVOKE", target = INTERACT,
            shift = At.Shift.AFTER))
    private void hadron$endRefused(Entity entity, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        EntityState state = (EntityState) this;
        if (state.hadron$interactRefused()) {
            state.hadron$setInteractRefused(false);
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
