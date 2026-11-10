package dev.iredsc.hadron.neoforge.mixin.block;

import dev.iredsc.hadron.neoforge.BlockHooks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The tool a block's drops are worked out with. Machines that harvest (AE2's annihilation plane, Mekanism's digital
 * miner, Create's drill) work out the drops with their own tool and then remove the block, so the removal's
 * {@code BlockBreakEvent} can name that tool.
 */
@Mixin(Block.class)
abstract class BlockDropsToolMixin {
    @Inject(method = "getDrops(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;", at = @At("HEAD"))
    private static void hadron$harvestTool(BlockState state, ServerLevel level, BlockPos pos,
            @Nullable BlockEntity blockEntity, @Nullable Entity entity, ItemStack tool,
            CallbackInfoReturnable<List<ItemStack>> cir) {
        BlockHooks.harvesting(level, pos, state, tool);
    }
}
