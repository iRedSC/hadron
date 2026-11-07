package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Building a snow or iron golem: vanilla clears the pattern ignoring whether each block's removal was refused, then
 * spawns the golem. Asked as one action before anything is cleared instead.
 */
@Mixin(CarvedPumpkinBlock.class)
public abstract class CarvedPumpkinBlockMixin {
    @WrapMethod(method = "spawnGolemInWorld")
    private static void hadron$build(Level level, BlockPattern.BlockPatternMatch match, Entity golem, BlockPos pos,
            Operation<Void> original) {
        BlockHooks.patternBuild(level, () -> {
            if (!BlockHooks.cancelPatternBuild(level, match, golem)) original.call(level, match, golem, pos);
        });
    }
}
