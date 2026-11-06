package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.Objects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * An effect ran out on a living entity. After the fact and not cancellable, so a timed power can never be made
 * permanent by a plugin that cancels everything.
 */
public class EntityPotionEffectExpireEvent extends EntityEvent {
    private final MobEffectInstance effect;

    public EntityPotionEffectExpireEvent(LivingEntity entity, MobEffectInstance effect, Actor actor) {
        super(entity, actor);
        this.effect = Objects.requireNonNull(effect, "effect");
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** The effect that ran out. */
    public MobEffectInstance getEffect() {
        return effect;
    }
}
