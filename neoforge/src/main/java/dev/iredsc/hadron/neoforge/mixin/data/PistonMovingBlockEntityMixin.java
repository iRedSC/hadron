package dev.iredsc.hadron.neoforge.mixin.data;

import dev.iredsc.hadron.neoforge.DataHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A moving block that lands hands its carried position data to where it stands. */
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", shift = At.Shift.AFTER))
    private static void hadron$land(Level level, BlockPos pos, BlockState state, PistonMovingBlockEntity blockEntity,
            CallbackInfo ci) {
        DataHooks.pistonLand(level, blockEntity);
    }

    @Inject(method = "finalTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z", shift = At.Shift.AFTER))
    private void hadron$landNow(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        DataHooks.pistonLand(self.getLevel(), (PistonMovingBlockEntity) self);
    }
}
