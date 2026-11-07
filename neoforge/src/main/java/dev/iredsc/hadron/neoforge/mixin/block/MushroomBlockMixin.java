package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** A mushroom growing into a giant one: one {@code StructureGrowEvent}. */
@Mixin(MushroomBlock.class)
public abstract class MushroomBlockMixin {
    @WrapMethod(method = "growMushroom")
    private boolean hadron$growMushroom(ServerLevel level, BlockPos pos, BlockState state, RandomSource random,
            Operation<Boolean> original) {
        return BlockHooks.growStructure(level, pos, state, () -> original.call(level, pos, state, random));
    }
}
