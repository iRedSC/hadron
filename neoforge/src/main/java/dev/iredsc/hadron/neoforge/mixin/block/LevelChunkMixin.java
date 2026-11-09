package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.AreaIndex;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** A new chunk settling its generated blocks as it loads: vanilla's own, never an outsider's in a watched area. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    @WrapMethod(method = "postProcessGeneration")
    private void hadron$settle(Operation<Void> original) {
        AreaIndex work = AreaIndex.enterVanillaWork(level);
        try {
            original.call();
        } finally {
            AreaIndex.exitVanillaWork(work);
        }
    }
}
