package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.server.commands.CloneCommands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Empties a replaced container only once its replacement is allowed: see {@link BlockHooks#deferClear}. */
@Mixin(CloneCommands.class)
public abstract class CloneCommandsMixin {
    @WrapOperation(method = "clone", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/Clearable;tryClear(Ljava/lang/Object;)V"))
    private static void hadron$deferClear(Object blockEntity, Operation<Void> original) {
        BlockHooks.deferClear(blockEntity, () -> original.call(blockEntity));
    }
}
