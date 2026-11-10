package dev.iredsc.hadron.harness.mixin;

import dev.iredsc.hadron.harness.TestSideEffects;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player made hungrier (attacking, mining, sprinting), for {@code SideEffects}: NeoForge has no event for it. */
@Mixin(Player.class)
abstract class PlayerExhaustionMixin {
    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"))
    private void hadron$recordExhaustion(float exhaustion, CallbackInfo ci) {
        TestSideEffects.foodExhausted((Player) (Object) this, exhaustion);
    }
}
