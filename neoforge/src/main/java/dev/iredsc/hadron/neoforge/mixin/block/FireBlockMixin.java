package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire burning a block: one {@code BlockBurnEvent}, before the block can set anything off. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    /** The burn replaces the block with fire or air; that write is the burn, not a separate ignite or break. */
    @WrapMethod(method = "checkBurnOut")
    private void hadron$burnOnce(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face,
            Operation<Void> original) {
        long previous = BlockHooks.suppressAt(pos);
        try {
            original.call(level, pos, chance, random, age, face);
        } finally {
            BlockHooks.restoreSuppressAt(previous);
        }
    }

    /** Only reached once the random roll says it burns, so a callback is built only for real burns. */
    @Inject(method = "checkBurnOut", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;onCaughtFire(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void hadron$burn(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face,
            CallbackInfo ci) {
        if (BlockHooks.cancelBurn(level, pos)) ci.cancel();
    }
}
