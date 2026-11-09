package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A click on an entity that failed in {@code Entity.interact} (a plugin refused putting a lead on or taking one off)
 * ends there: vanilla would otherwise try the held item next, so a refused unleash with shears in hand would shear
 * the sheep. Judged by that call's own result, so nothing another call does meanwhile can fail this one. Vanilla's
 * mobs never fail here (a mob turns its own failure into a pass).
 */
@Mixin(Player.class)
abstract class PlayerInteractMixin {
    @WrapOperation(method = "interactOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult hadron$interact(Entity entity, Player player, InteractionHand hand,
            Operation<InteractionResult> original, @Share("failed") LocalBooleanRef failed) {
        InteractionResult result = original.call(entity, player, hand);
        failed.set(result == InteractionResult.FAIL);
        return result;
    }

    @Inject(method = "interactOn", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;interactLivingEntity(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private void hadron$endFailed(Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir,
            @Share("failed") LocalBooleanRef failed) {
        if (failed.get()) cir.setReturnValue(InteractionResult.FAIL);
    }
}
