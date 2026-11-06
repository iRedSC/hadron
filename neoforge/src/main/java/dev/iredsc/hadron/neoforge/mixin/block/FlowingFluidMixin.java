package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;

/** Every fluid flowing into a block, vanilla's and mods' that extend {@code FlowingFluid}. */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @WrapMethod(method = "spreadTo")
    private void hadron$flow(LevelAccessor level, BlockPos pos, BlockState blockState, Direction direction,
            FluidState fluidState, Operation<Void> original) {
        if (BlockHooks.cancelFlow(level, pos, blockState, direction, fluidState)) return;
        BlockHooks.suppress();
        try {
            original.call(level, pos, blockState, direction, fluidState);
        } finally {
            BlockHooks.unsuppress();
        }
    }
}
