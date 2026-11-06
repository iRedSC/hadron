package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** Bone meal from any user: everything one application grows is one {@code BlockFertilizeEvent}. */
@Mixin(BoneMealItem.class)
public abstract class BoneMealItemMixin {
    @WrapMethod(method = "applyBonemeal")
    private static boolean hadron$applyBonemeal(ItemStack stack, Level level, BlockPos pos, Player player,
            Operation<Boolean> original) {
        if (!(level instanceof ServerLevel server)) return original.call(stack, level, pos, player);
        return BlockHooks.fertilize(server, pos, player, stack, () -> original.call(stack, level, pos, player));
    }

    @WrapMethod(method = "growWaterPlant")
    private static boolean hadron$growWaterPlant(ItemStack stack, Level level, BlockPos pos, Direction side,
            Operation<Boolean> original) {
        if (!(level instanceof ServerLevel server)) return original.call(stack, level, pos, side);
        return BlockHooks.fertilize(server, pos, null, stack, () -> original.call(stack, level, pos, side));
    }
}
