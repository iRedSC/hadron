package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.IShearable;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shearing a mob by hand. NeoForge moved every vanilla mob's shearing here, behind {@code IShearable}, so a mod's
 * shearable mob is covered too. Refused, the shears pass, as on a mob that isn't ready.
 */
@Mixin(ShearsItem.class)
abstract class ShearsItemMixin {
    @WrapOperation(method = "interactLivingEntity", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/IShearable;isShearable(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean hadron$shear(IShearable target, @Nullable Player player, ItemStack stack, Level level,
            BlockPos pos, Operation<Boolean> original) {
        return original.call(target, player, stack, level, pos) && !EntityHandling.refuseShear(target, stack, player);
    }
}
