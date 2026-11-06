package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lava meeting water forming obsidian, cobblestone or basalt. */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockMixin {
    @WrapOperation(method = "shouldSpreadLiquid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean hadron$form(Level level, BlockPos pos, BlockState formed, Operation<Boolean> original) {
        if (BlockHooks.cancelForm(level, pos, formed)) return false;
        long previous = BlockHooks.suppressAt(pos);
        try {
            return original.call(level, pos, formed);
        } finally {
            BlockHooks.restoreSuppressAt(previous);
        }
    }
}
