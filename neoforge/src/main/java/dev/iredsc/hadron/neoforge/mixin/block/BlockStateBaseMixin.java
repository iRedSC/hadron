package dev.iredsc.hadron.neoforge.mixin.block;

import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Using a block, by anything with a player: real players and fake players alike reach these, whichever game mode or
 * mod code calls them. A cancelled use with an item skips the block's reaction but still lets the item act.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @Shadow
    protected abstract BlockState asState();

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void hadron$useWithItem(ItemStack stack, Level level, Player player, InteractionHand hand,
            BlockHitResult hit, CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (BlockHooks.cancelUse(asState(), level, player, hand, stack, hit, true)) {
            cir.setReturnValue(ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION);
        }
    }

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void hadron$useWithoutItem(Level level, Player player, BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (player != null && BlockHooks.cancelUse(asState(), level, player, InteractionHand.MAIN_HAND,
                player.getMainHandItem(), hit, false)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
