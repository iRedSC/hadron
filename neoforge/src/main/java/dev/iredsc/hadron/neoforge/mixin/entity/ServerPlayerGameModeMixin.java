package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.EntitySpawnRefunds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/** A player's item use whose every spawn was refused (a snowball, a boat, a minecart) costs nothing. */
@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
    @WrapMethod(method = "useItem")
    private InteractionResult hadron$refundUse(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
            Operation<InteractionResult> original) {
        return EntitySpawnRefunds.using(player, () -> original.call(player, level, stack, hand));
    }

    @WrapMethod(method = "useItemOn")
    private InteractionResult hadron$refundUseOn(ServerPlayer player, Level level, ItemStack stack,
            InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        return EntitySpawnRefunds.using(player, () -> original.call(player, level, stack, hand, hit));
    }
}
