package dev.iredsc.hadron.neoforge.mixin.data;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.DataBlocks;
import dev.iredsc.hadron.neoforge.DataChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block-position data goes when its block is replaced by a different one. Every block write in a loaded chunk, from
 * vanilla or any mod, passes here; a chunk without position data pays one null check and allocates nothing.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin implements DataChunk {
    @Unique
    private DataBlocks hadron$blocks;

    @Override
    public DataBlocks hadron$blocks() {
        return hadron$blocks;
    }

    @Override
    public void hadron$setBlocks(DataBlocks blocks) {
        hadron$blocks = blocks;
    }

    @ModifyExpressionValue(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/"
            + "chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState hadron$forgetReplaced(BlockState old, @Local(argsOnly = true) BlockPos pos,
            @Local(argsOnly = true) BlockState state) {
        DataBlocks blocks = hadron$blocks;
        if (blocks != null && old != state && old.getBlock() != state.getBlock()) {
            blocks.forget((LevelChunk) (Object) this, pos);
        }
        return old;
    }
}
