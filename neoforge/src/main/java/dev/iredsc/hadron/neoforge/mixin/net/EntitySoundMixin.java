package dev.iredsc.hadron.neoforge.mixin.net;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.NetVisibility;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** An entity's own sounds name it as their source, so a hidden entity's hurt sounds and footsteps stay hidden. */
@Mixin(Entity.class)
abstract class EntitySoundMixin {
    @WrapMethod(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V")
    private void hadron$source(SoundEvent sound, float volume, float pitch, Operation<Void> original) {
        NetVisibility.playingSound((Entity) (Object) this, () -> original.call(sound, volume, pitch));
    }
}
