package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Leaves decaying: asked before they drop anything, and only for leaves that are decaying. */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {
    @ModifyExpressionValue(method = "randomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/LeavesBlock;decaying(Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean hadron$decay(boolean decaying, @Local(argsOnly = true) BlockState state,
            @Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) BlockPos pos) {
        return decaying && !BlockHooks.cancelLeavesDecay(level, pos, state);
    }
}
