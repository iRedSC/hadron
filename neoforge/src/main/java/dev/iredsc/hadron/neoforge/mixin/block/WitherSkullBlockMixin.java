package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Building the wither, as one action: see {@link CarvedPumpkinBlockMixin}. */
@Mixin(WitherSkullBlock.class)
public abstract class WitherSkullBlockMixin {
    private static final String CHECK_SPAWN = "checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)V";

    @WrapMethod(method = CHECK_SPAWN)
    private static void hadron$build(Level level, BlockPos pos, SkullBlockEntity skull, Operation<Void> original) {
        BlockHooks.patternBuild(level, () -> original.call(level, pos, skull));
    }

    /** Before the pattern is cleared, and before anyone is told a wither was summoned. */
    @Inject(method = CHECK_SPAWN, cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/CarvedPumpkinBlock;clearPatternBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/pattern/BlockPattern$BlockPatternMatch;)V"))
    private static void hadron$ask(Level level, BlockPos pos, SkullBlockEntity skull, CallbackInfo ci,
            @Local BlockPattern.BlockPatternMatch match, @Local WitherBoss wither) {
        if (BlockHooks.cancelPatternBuild(level, match, wither)) ci.cancel();
    }
}
