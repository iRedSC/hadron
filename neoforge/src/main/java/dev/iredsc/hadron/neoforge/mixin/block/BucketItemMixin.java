package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Buckets pouring (by players, dispensers and mods using bucket items) and players filling them. */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    @WrapMethod(method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z")
    private boolean hadron$empty(Player player, Level level, BlockPos pos, BlockHitResult hit, ItemStack container,
            Operation<Boolean> original) {
        if (BlockHooks.cancelBucketEmpty((BucketItem) (Object) this, player, level, pos, hit, container)) {
            return false;
        }
        BlockHooks.suppressAt(level, pos);
        try {
            return original.call(player, level, pos, hit, container);
        } finally {
            BlockHooks.restoreSuppressAt();
        }
    }

    @WrapOperation(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack hadron$fill(BucketPickup pickup, Player player, LevelAccessor level, BlockPos pos,
            BlockState state, Operation<ItemStack> original, @Local(argsOnly = true) InteractionHand hand,
            @Local BlockHitResult hit) {
        if (BlockHooks.cancelBucketFill(player, level, pos, state, player.getItemInHand(hand), hit.getDirection())) {
            return ItemStack.EMPTY;
        }
        BlockHooks.suppressAt(level, pos);
        try {
            return original.call(pickup, player, level, pos, state);
        } finally {
            BlockHooks.restoreSuppressAt();
        }
    }
}
