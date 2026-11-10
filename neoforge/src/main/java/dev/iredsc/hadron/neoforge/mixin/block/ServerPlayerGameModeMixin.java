package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * A player mining a block is reported once, by NeoForge's break event inside, with the item in their main hand as the
 * tool (vanilla harvests with it); the block removal that follows is not reported again.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow protected ServerLevel level;
    @Shadow @Final protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean hadron$destroyOnce(BlockPos pos, Operation<Boolean> original) {
        BlockHooks.suppressAt(level, pos);
        BlockHooks.Mining previous = BlockHooks.enterMining(player, level, pos);
        try {
            return original.call(pos);
        } finally {
            BlockHooks.exitMining(previous);
            BlockHooks.restoreSuppressAt();
        }
    }
}
