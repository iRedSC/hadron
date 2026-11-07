package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.EntitySpawnRefunds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/** A mob bucket's release is one action with its pour: a refused mob takes its water back with it. */
@Mixin(MobBucketItem.class)
public abstract class MobBucketItemMixin {
    @WrapMethod(method = "checkExtraContent")
    private void hadron$release(Player player, Level level, ItemStack stack, BlockPos pos, Operation<Void> original) {
        EntitySpawnRefunds.releasing(level, pos, () -> original.call(player, level, stack, pos));
    }
}
