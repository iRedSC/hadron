package dev.iredsc.hadron.neoforge.mixin.entity;

import net.minecraft.world.effect.MobEffectInstance;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An effect's hidden chain, which {@code DataEffectCauses} walks, and copies whole when an effect is applied. */
@Mixin(MobEffectInstance.class)
public interface MobEffectInstanceAccessor {
    @Accessor("hiddenEffect")
    @Nullable MobEffectInstance hadron$hiddenEffect();

    @Accessor("hiddenEffect")
    void hadron$setHiddenEffect(@Nullable MobEffectInstance hidden);
}
