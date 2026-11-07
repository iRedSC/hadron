package dev.iredsc.hadron.neoforge.mixin.data;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.DataHooks;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A pushed block's position data rides on its moving block, taken before the push overwrites its origin. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    @ModifyExpressionValue(method = "moveBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/"
            + "block/piston/MovingPistonBlock;newMovingBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/"
            + "level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/core/Direction;ZZ)Lnet/minecraft/world/level/block/entity/BlockEntity;"))
    private BlockEntity hadron$carryData(BlockEntity created, @Local(argsOnly = true) Level level,
            @Local(argsOnly = true) Direction facing, @Local(argsOnly = true) boolean extending) {
        DataHooks.pistonTake(level, created, facing, extending);
        return created;
    }
}
