package dev.iredsc.hadron.neoforge.mixin.block;

import dev.iredsc.hadron.neoforge.PortKey;
import org.jspecify.annotations.Nullable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.AreaIndex;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While a modded block entity ticks, it is the actor: {@link Actor.Machine} at its position, with whoever was
 * remembered behind it. The actor is kept per block entity and rebuilt only when its attribution changes.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @Unique
    private Actor.@Nullable Machine hadron$machine;
    /** 0 not checked yet, 1 vanilla, 2 modded. */
    @Unique
    private byte hadron$origin;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void hadron$tickAsMachine(BlockEntityTicker<BlockEntity> ticker, Level level, BlockPos pos,
            BlockState state, BlockEntity blockEntity, Operation<Void> original) {
        if (hadron$origin == 0) hadron$origin = BlockHooks.modded(blockEntity.getType()) ? (byte) 2 : (byte) 1;
        if (!(level instanceof ServerLevel server)) {
            original.call(ticker, level, pos, state, blockEntity);
            return;
        }
        if (hadron$origin == 1) {
            AreaIndex.enterVanillaWork(server);
            try {
                original.call(ticker, level, pos, state, blockEntity);
            } finally {
                AreaIndex.exitVanillaWork(server);
            }
            return;
        }
        hadron$machine = BlockHooks.machine(hadron$machine, server, blockEntity);
        long scope = Actors.Port.enter(PortKey.KEY, hadron$machine);
        try {
            original.call(ticker, level, pos, state, blockEntity);
        } finally {
            Actors.Port.exit(PortKey.KEY, scope);
        }
    }
}
