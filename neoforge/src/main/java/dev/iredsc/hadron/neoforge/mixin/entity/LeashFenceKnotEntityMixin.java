package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clicking a knot while holding leashed mobs ties them to it. Vanilla takes the knot down when the click tied nothing,
 * which would loose every mob already on it; a click whose ties were all refused keeps the knot.
 */
@Mixin(LeashFenceKnotEntity.class)
abstract class LeashFenceKnotEntityMixin {
    @WrapOperation(method = "interact", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Leashable;setLeashedTo(Lnet/minecraft/world/entity/Entity;Z)V"))
    private void hadron$tie(Leashable leashable, Entity knot, boolean broadcast, Operation<Void> original,
            @Local(argsOnly = true) Player player) {
        if (!EntityHandling.refuseTie(leashable, knot, player)) original.call(leashable, knot, broadcast);
    }

    @Inject(method = "interact", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/LeashFenceKnotEntity;discard()V"))
    private void hadron$keepKnot(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        LeashFenceKnotEntity self = (LeashFenceKnotEntity) (Object) this;
        if (EntityHandling.refusedTie(self.level(), self.getPos(), player)) {
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
