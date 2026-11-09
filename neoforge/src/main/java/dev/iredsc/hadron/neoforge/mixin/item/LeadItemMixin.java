package dev.iredsc.hadron.neoforge.mixin.item;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.iredsc.hadron.neoforge.EntityHandling;
import dev.iredsc.hadron.neoforge.EntityState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clicking a fence with a lead ties every mob the player holds to a knot there; each tie is asked on its own. The
 * click remembers whether it made the knot and how many ties went through, so a click whose ties were all refused
 * takes down only the knot it made, makes no placement sound or attach vibration, and ends failed
 * ({@code ServerPlayerGameModeClickMixin}) so no advancement counts it.
 */
@Mixin(LeadItem.class)
abstract class LeadItemMixin {
    @WrapOperation(method = "bindPlayerMobs", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/LeashFenceKnotEntity;getOrCreateKnot(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/entity/decoration/LeashFenceKnotEntity;"))
    private static LeashFenceKnotEntity hadron$knot(Level level, BlockPos pos, Operation<LeashFenceKnotEntity> original,
            @Share("made") LocalRef<LeashFenceKnotEntity> made) {
        boolean existed = EntityHandling.hasKnot(level, pos);
        LeashFenceKnotEntity knot = original.call(level, pos);
        if (!existed) made.set(knot);
        return knot;
    }

    /** Held back until a tie goes through: a click that ties nothing places nothing. */
    @WrapWithCondition(method = "bindPlayerMobs", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/LeashFenceKnotEntity;playPlacementSound()V"))
    private static boolean hadron$holdSound(LeashFenceKnotEntity knot,
            @Share("soundHeld") LocalBooleanRef soundHeld) {
        soundHeld.set(true);
        return false;
    }

    @WrapOperation(method = "bindPlayerMobs", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Leashable;setLeashedTo(Lnet/minecraft/world/entity/Entity;Z)V"))
    private static void hadron$tie(Leashable leashable, Entity knot, boolean broadcast, Operation<Void> original,
            @Local(argsOnly = true) Player player, @Share("tied") LocalIntRef tied,
            @Share("refused") LocalIntRef refused, @Share("soundHeld") LocalBooleanRef soundHeld) {
        if (EntityHandling.refuseTie(leashable, knot, player)) {
            refused.set(refused.get() + 1);
            return;
        }
        original.call(leashable, knot, broadcast);
        if (tied.get() == 0 && soundHeld.get() && knot instanceof LeashFenceKnotEntity placed) {
            placed.playPlacementSound();
        }
        tied.set(tied.get() + 1);
    }

    @WrapWithCondition(method = "bindPlayerMobs", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V"))
    private static boolean hadron$attachSignal(Level level, Holder<GameEvent> event, BlockPos pos,
            GameEvent.Context context, @Share("tied") LocalIntRef tied) {
        return tied.get() > 0;
    }

    /** A click whose ties were all refused takes down the knot it made, and ends failed so nothing counts it. */
    @Inject(method = "bindPlayerMobs", at = @At("RETURN"), cancellable = true)
    private static void hadron$endRefused(Player player, Level level, BlockPos pos,
            CallbackInfoReturnable<InteractionResult> cir, @Share("made") LocalRef<LeashFenceKnotEntity> made,
            @Share("tied") LocalIntRef tied, @Share("refused") LocalIntRef refused) {
        if (tied.get() > 0 || refused.get() == 0) return;
        LeashFenceKnotEntity knot = made.get();
        if (knot != null) knot.discard();
        ((EntityState) player).hadron$setInteractRefused(true);
        cir.setReturnValue(InteractionResult.FAIL);
    }
}
