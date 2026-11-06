package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;

/** A sapling or azalea growing into a tree: one {@code StructureGrowEvent} for every block it places. */
@Mixin(TreeGrower.class)
public abstract class TreeGrowerMixin {
    @WrapMethod(method = "growTree")
    private boolean hadron$growTree(ServerLevel level, ChunkGenerator generator, BlockPos pos, BlockState state,
            RandomSource random, Operation<Boolean> original) {
        return BlockHooks.growStructure(level, pos, state, () -> original.call(level, generator, pos, state, random));
    }
}
