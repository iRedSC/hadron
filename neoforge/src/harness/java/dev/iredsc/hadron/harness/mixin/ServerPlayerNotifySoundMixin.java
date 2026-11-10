package dev.iredsc.hadron.harness.mixin;

import dev.iredsc.hadron.harness.TestSideEffects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A sound sent to one player alone (a locked chest's), for {@code SideEffects}: it never goes through a world. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerNotifySoundMixin {
    @Inject(method = "playNotifySound", at = @At("HEAD"))
    private void hadron$recordNotifySound(SoundEvent sound, SoundSource source, float volume, float pitch,
            CallbackInfo ci) {
        TestSideEffects.notifySoundSent((ServerPlayer) (Object) this, sound, source);
    }
}
