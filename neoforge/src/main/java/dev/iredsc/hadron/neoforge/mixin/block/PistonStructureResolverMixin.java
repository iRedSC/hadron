package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Shows the resolver air where a retracting piston's head still is, so Hadron can ask what it will pull. */
@Mixin(PistonStructureResolver.class)
abstract class PistonStructureResolverMixin {
    @WrapOperation(method = {"resolve", "addBlockLine", "addBranchingBlocks"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState hadron$withoutHead(Level level, BlockPos pos, Operation<BlockState> original) {
        BlockPos hidden = BlockHooks.pistonHiddenHead;
        return hidden != null && hidden.equals(pos) ? Blocks.AIR.defaultBlockState() : original.call(level, pos);
    }
}
