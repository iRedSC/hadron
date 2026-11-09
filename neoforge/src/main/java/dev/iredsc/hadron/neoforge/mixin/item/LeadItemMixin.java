package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clicking a fence with a lead ties every mob the player holds to a knot there; each tie is asked on its own. */
@Mixin(LeadItem.class)
abstract class LeadItemMixin {
    @WrapOperation(method = "bindPlayerMobs", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Leashable;setLeashedTo(Lnet/minecraft/world/entity/Entity;Z)V"))
    private static void hadron$tie(Leashable leashable, Entity knot, boolean broadcast, Operation<Void> original,
            @Local(argsOnly = true) Player player) {
        if (!EntityHandling.refuseTie(leashable, knot, player)) original.call(leashable, knot, broadcast);
    }

    @Inject(method = "bindPlayerMobs", at = @At("RETURN"))
    private static void hadron$dropUnusedKnot(Player player, Level level, BlockPos pos,
            CallbackInfoReturnable<InteractionResult> cir) {
        EntityHandling.dropUnusedKnot(level, pos, player);
    }
}
