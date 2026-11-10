package dev.iredsc.hadron.api.event.entity;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffectInstance;

/** How the effect events copy a {@link MobEffectInstance}, so every copy is whole. */
final class EffectCopies {
    private EffectCopies() {}

    /**
     * A copy with everything the instance saves: its hidden weaker effect (which resumes when this one runs out) and
     * whatever the loader adds, such as NeoForge's cures. {@code new MobEffectInstance(effect)} drops the hidden one.
     */
    static MobEffectInstance copy(MobEffectInstance effect) {
        Objects.requireNonNull(effect, "effect");
        MobEffectInstance copy = effect.save() instanceof CompoundTag saved ? MobEffectInstance.load(saved) : null;
        if (copy == null) {
            throw new IllegalStateException("Minecraft couldn't reload the effect it just saved (" + effect + "); "
                    + "this is a Hadron bug, so report it with this message");
        }
        return copy;
    }
}
