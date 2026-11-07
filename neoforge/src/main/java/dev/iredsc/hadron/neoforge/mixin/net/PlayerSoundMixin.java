package dev.iredsc.hadron.neoforge.mixin.net;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.NetVisibility;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** A player's own sounds (Player overrides Entity's) name them as their source, for visibility. */
@Mixin(Player.class)
abstract class PlayerSoundMixin {
    @WrapMethod(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V")
    private void hadron$source(SoundEvent sound, float volume, float pitch, Operation<Void> original) {
        NetVisibility.playingSound((Player) (Object) this, () -> original.call(sound, volume, pitch));
    }
}
