package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A structure placed over a container (a structure block, {@code /place template}) empties it only once the
 * replacement is allowed: see {@link BlockHooks#deferClear}. World generation empties at once, as vanilla does.
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin {
    @WrapOperation(method = "placeInWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/Clearable;tryClear(Ljava/lang/Object;)V"))
    private void hadron$deferClear(Object blockEntity, Operation<Void> original,
            @Local(argsOnly = true) ServerLevelAccessor level) {
        BlockHooks.deferClear(level, blockEntity, () -> original.call(blockEntity));
    }
}
