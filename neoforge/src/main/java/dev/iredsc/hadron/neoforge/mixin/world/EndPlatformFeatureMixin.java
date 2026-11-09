package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.AreaIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The obsidian platform built under someone arriving in the End, by their tick in the world they left: vanilla's own
 * work there, silent in a watched area. Only a live level is marked; world generation's regions run off-thread.
 */
@Mixin(EndPlatformFeature.class)
abstract class EndPlatformFeatureMixin {
    @WrapMethod(method = "createEndPlatform")
    private static void hadron$vanilla(ServerLevelAccessor level, BlockPos pos, boolean dropBlocks,
            Operation<Void> original) {
        if (!(level instanceof ServerLevel server)) {
            original.call(level, pos, dropBlocks);
            return;
        }
        AreaIndex.enterVanillaWork(server);
        try {
            original.call(level, pos, dropBlocks);
        } finally {
            AreaIndex.exitVanillaWork(server);
        }
    }
}
