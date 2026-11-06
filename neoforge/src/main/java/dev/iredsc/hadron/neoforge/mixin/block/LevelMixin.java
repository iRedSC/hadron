package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The generic block chokepoints: every {@code setBlock} and {@code destroyBlock} on a live level. */
@Mixin(Level.class)
public abstract class LevelMixin {
    /**
     * Rides on setBlock's first check, so a refused change returns false exactly as one outside the world would, and
     * nothing is allocated per call (an {@code @Inject} would allocate its callback object every time).
     */
    @ModifyExpressionValue(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean hadron$cancelSetBlock(boolean outside, @Local(argsOnly = true) BlockPos pos,
            @Local(argsOnly = true) BlockState state) {
        return outside || BlockHooks.cancelSetBlock((Level) (Object) this, pos, state);
    }

    /** Fires before anything drops, and keeps the setBlock inside from firing the same break again. */
    @WrapMethod(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z")
    private boolean hadron$destroyBlock(BlockPos pos, boolean dropBlock, Entity entity, int recursionLeft,
            Operation<Boolean> original) {
        if (BlockHooks.cancelDestroy((Level) (Object) this, pos, entity)) return false;
        long previous = BlockHooks.suppressAt(pos);
        try {
            return original.call(pos, dropBlock, entity, recursionLeft);
        } finally {
            BlockHooks.restoreSuppressAt(previous);
        }
    }
}
