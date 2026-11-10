package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An effect ran out on a living entity. After the fact and not cancellable, so a timed power can never be made
 * permanent by a plugin that cancels everything.
 */
@FiredBy(core = FULL)
public class EntityPotionEffectExpireEvent extends EntityEvent {
    private final MobEffectInstance effect;

    public EntityPotionEffectExpireEvent(LivingEntity entity, MobEffectInstance effect, Actor actor) {
        super(entity, actor);
        this.effect = new MobEffectInstance(Objects.requireNonNull(effect, "effect"));
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** The effect that ran out. A new copy each call, so editing it changes nothing. */
    public MobEffectInstance getEffect() {
        return new MobEffectInstance(effect);
    }
}
