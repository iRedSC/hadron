package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FungusBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** A nether fungus growing into a huge one: one {@code StructureGrowEvent}. */
@Mixin(FungusBlock.class)
public abstract class FungusBlockMixin {
    @WrapMethod(method = "performBonemeal")
    private void hadron$growFungus(ServerLevel level, RandomSource random, BlockPos pos, BlockState state,
            Operation<Void> original) {
        BlockHooks.growStructure(level, pos, state, () -> {
            original.call(level, random, pos, state);
            return true;
        });
    }
}
