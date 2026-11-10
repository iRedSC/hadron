package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.ShearsDispenseItemBehavior;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.IShearable;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A dispenser shearing the first ready mob in front of it, as the machine on the actor stack. A refused mob is
 * skipped like one that isn't ready; with none left the dispenser fails and its shears take no wear.
 */
@Mixin(ShearsDispenseItemBehavior.class)
abstract class ShearsDispenseItemBehaviorMixin {
    @WrapOperation(method = "tryShearLivingEntity", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/IShearable;isShearable(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean hadron$shear(IShearable target, @Nullable Player player, ItemStack stack, Level level,
            BlockPos pos, Operation<Boolean> original) {
        return original.call(target, player, stack, level, pos) && !EntityHandling.refuseShear(target, stack, null);
    }
}
