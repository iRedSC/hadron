package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A click on a block that failed in the block's own use (a plugin refused every tie to a fence) ends there: vanilla
 * would otherwise try the item in hand next, placing a block or tying the mobs again with a lead. Judged by that
 * call's own result, so nothing another call does meanwhile can fail this one. The one vanilla block that fails
 * here, a note block whose NeoForge note change was cancelled, then doesn't use the held item either.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeClickMixin {
    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;useWithoutItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult hadron$use(BlockState state, Level level, Player player, BlockHitResult hit,
            Operation<InteractionResult> original, @Share("failed") LocalBooleanRef failed) {
        InteractionResult result = original.call(state, level, player, hit);
        failed.set(result == InteractionResult.FAIL);
        return result;
    }

    @Inject(method = "useItemOn", cancellable = true, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/InteractionResult;consumesAction()Z"))
    private void hadron$endFailed(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
            BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir, @Share("failed") LocalBooleanRef failed) {
        if (failed.get()) cir.setReturnValue(InteractionResult.FAIL);
    }
}
