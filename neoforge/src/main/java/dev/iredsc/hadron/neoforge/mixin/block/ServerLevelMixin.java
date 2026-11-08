package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Blocks' own ticks and weather, so what they change is reported as nature, or as an unknown mod; and vanilla blocks'
 * queued block events (a piston's push, a note block, a bell), which are vanilla's own working.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void hadron$randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
            Operation<Void> original) {
        BlockHooks.enterTick(state, pos);
        try {
            original.call(state, level, pos, random);
        } finally {
            BlockHooks.exitTick();
        }
    }

    @WrapOperation(method = "tickBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void hadron$scheduledTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
            Operation<Void> original) {
        BlockHooks.enterTick(state, pos);
        try {
            original.call(state, level, pos, random);
        } finally {
            BlockHooks.exitTick();
        }
    }

    @WrapMethod(method = "tickPrecipitation")
    private void hadron$precipitation(BlockPos pos, Operation<Void> original) {
        BlockHooks.enterForming();
        try {
            original.call(pos);
        } finally {
            BlockHooks.exitForming();
        }
    }

    /** Vanilla's own: never an outsider's in a watched area. A mod's block event is left as its own. */
    @WrapMethod(method = "doBlockEvent")
    private boolean hadron$blockEvent(BlockEventData event, Operation<Boolean> original) {
        if (BlockHooks.modded(event.block())) return original.call(event);
        BlockHooks.enterVanillaMachine();
        try {
            return original.call(event);
        } finally {
            BlockHooks.exitVanillaMachine();
        }
    }
}
