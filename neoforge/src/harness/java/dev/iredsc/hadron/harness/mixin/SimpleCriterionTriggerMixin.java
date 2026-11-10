package dev.iredsc.hadron.harness.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.harness.TestSideEffects;
import java.util.function.Predicate;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Every vanilla advancement trigger firing for a player, whether or not an advancement listens for it, for
 * {@code SideEffects}: every vanilla trigger but the one that never fires goes through here. Wrapped rather than
 * entered, so what an ambient trigger causes while it runs (an advancement it completes, its rewards) can be told apart.
 */
@Mixin(SimpleCriterionTrigger.class)
abstract class SimpleCriterionTriggerMixin {
    @WrapMethod(method = "trigger")
    private void hadron$recordTrigger(ServerPlayer player, Predicate<?> test, Operation<Void> original) {
        TestSideEffects.criterionTriggered((CriterionTrigger<?>) (Object) this, player,
                () -> original.call(player, test));
    }
}
