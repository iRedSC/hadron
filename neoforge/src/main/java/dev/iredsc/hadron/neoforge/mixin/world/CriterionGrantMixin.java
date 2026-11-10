package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.HeldEffects;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A criterion met inside a {@link HeldEffects} hold is granted only once the action stands. The criterion is still
 * tested as the trigger fires, against the world and item as they are then; only the grant, and the advancement and
 * rewards it may complete, wait.
 */
@Mixin(SimpleCriterionTrigger.class)
abstract class CriterionGrantMixin {
    @WrapOperation(method = "trigger", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/advancements/CriterionTrigger$Listener;run(Lnet/minecraft/server/PlayerAdvancements;)V"))
    private void hadron$holdGrant(CriterionTrigger.Listener<?> listener, PlayerAdvancements advancements,
            Operation<Void> original) {
        if (HeldEffects.holding()) {
            HeldEffects.hold(() -> original.call(listener, advancements));
            return;
        }
        original.call(listener, advancements);
    }
}
