package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Lighting TNT is one decision: vanilla spawns the primed TNT before it removes the block, so refusing only the
 * removal would leave both. The break is asked before anything happens, and the removal obeys the answer.
 */
@Mixin(TntBlock.class)
public abstract class TntBlockMixin {
    /** Flint and steel or a fire charge: refused before the primed TNT spawns or the item loses durability, and the
     * item doesn't get a turn afterwards. */
    @WrapMethod(method = "useItemOn")
    private ItemInteractionResult hadron$light(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit, Operation<ItemInteractionResult> original) {
        if (level instanceof ServerLevel && (stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE))
                && BlockHooks.cancelIgnition(level, pos, state, player)) {
            BlockHooks.forgetIgnition();
            // Not FAIL: that lets the item act next, and flint and steel would light a fire on top of the TNT.
            return ItemInteractionResult.CONSUME;
        }
        return original.call(stack, state, level, pos, player, hand, hit);
    }

    /** Every other way TNT is lit (a dispenser, a flaming arrow, a mod) passes here before it is primed. */
    @WrapMethod(method = "onCaughtFire")
    private void hadron$caughtFire(BlockState state, Level level, BlockPos pos, Direction face, LivingEntity igniter,
            Operation<Void> original) {
        if (level instanceof ServerLevel && BlockHooks.cancelIgnition(level, pos, state, igniter)) return;
        original.call(state, level, pos, face, igniter);
    }
}
