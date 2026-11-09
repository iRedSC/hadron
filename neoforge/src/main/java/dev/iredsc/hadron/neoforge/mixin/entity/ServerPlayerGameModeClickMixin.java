package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.EntityState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A click on a block that a plugin refused inside the block or the item (every tie to a fence refused) ends there,
 * failed: vanilla would count a fence's success toward advancements, or try the lead in hand on the fence again.
 * Creative mode's stack restore after the item's use is skipped too, which is fine: a refused click changed nothing.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeClickMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void hadron$clear(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
            BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        ((EntityState) player).hadron$setInteractRefused(false);
    }

    /** Right after the block's own use and the item's: a refusal inside either ends the click before it counts. */
    @Inject(method = "useItemOn", cancellable = true, at = {
            @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/world/level/block/state/BlockState;useWithoutItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"),
            @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;")})
    private void hadron$endRefused(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
            BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        EntityState state = (EntityState) player;
        if (!state.hadron$interactRefused()) return;
        state.hadron$setInteractRefused(false);
        cir.setReturnValue(InteractionResult.FAIL);
    }
}
