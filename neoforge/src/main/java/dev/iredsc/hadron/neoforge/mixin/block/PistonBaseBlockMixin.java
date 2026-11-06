package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;

/** A piston's move is its piston event; the block writes it makes on the way are not reported again. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    @WrapMethod(method = "moveBlocks")
    private boolean hadron$moveOnce(Level level, BlockPos pos, Direction facing, boolean extending,
            Operation<Boolean> original) {
        BlockHooks.suppress();
        try {
            return original.call(level, pos, facing, extending);
        } finally {
            BlockHooks.unsuppress();
        }
    }
}
