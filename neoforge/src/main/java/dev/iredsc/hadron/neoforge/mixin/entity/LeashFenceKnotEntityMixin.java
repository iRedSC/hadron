package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import dev.iredsc.hadron.neoforge.EntityHandling;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Clicking a knot while holding leashed mobs ties them to it, each tie asked on its own. Vanilla counts a refused tie
 * as tied, so it keeps the knot and the mobs already on it; only the attach vibration is held back when every tie was
 * refused.
 */
@Mixin(LeashFenceKnotEntity.class)
abstract class LeashFenceKnotEntityMixin {
    @WrapOperation(method = "interact", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Leashable;setLeashedTo(Lnet/minecraft/world/entity/Entity;Z)V"))
    private void hadron$tie(Leashable leashable, Entity knot, boolean broadcast, Operation<Void> original,
            @Local(argsOnly = true) Player player, @Share("tied") LocalIntRef tied,
            @Share("refused") LocalIntRef refused) {
        if (EntityHandling.refuseTie(leashable, knot, player)) {
            refused.set(refused.get() + 1);
            return;
        }
        original.call(leashable, knot, broadcast);
        tied.set(tied.get() + 1);
    }

    @WrapWithCondition(method = "interact", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/LeashFenceKnotEntity;gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/entity/Entity;)V"))
    private boolean hadron$attachSignal(LeashFenceKnotEntity self, Holder<GameEvent> event, @Nullable Entity cause,
            @Share("tied") LocalIntRef tied, @Share("refused") LocalIntRef refused) {
        return refused.get() == 0 || tied.get() > 0;
    }
}
