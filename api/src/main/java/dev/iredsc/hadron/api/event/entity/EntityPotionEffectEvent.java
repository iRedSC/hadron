package dev.iredsc.hadron.api.event.entity;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A registered effect (vanilla's or a mod's) is about to be added to, changed on, or removed from a living entity: a
 * potion, a beacon, a spell, milk. Cancel it and the entity keeps the effects it had. An effect running out is
 * {@link EntityPotionEffectExpireEvent}, which can't be cancelled.
 */
@FiredBy(core = FULL)
public class EntityPotionEffectEvent extends EntityEvent implements Cancellable {
    /** What is happening to the effect. */
    public enum Action {
        /** The entity didn't have it. */
        ADDED,
        /** The entity had it, and the new one may replace or extend it. */
        CHANGED,
        /** It is being taken away before it runs out: milk, a totem, a cure, a command. */
        REMOVED
    }

    private final Holder<MobEffect> effect;
    private final MobEffectInstance oldEffect;
    private final @Nullable MobEffectInstance newEffect;
    private final Action action;
    private boolean cancelled;

    public EntityPotionEffectEvent(LivingEntity entity, Holder<MobEffect> effect, MobEffectInstance oldEffect,
            @Nullable MobEffectInstance newEffect, Action action, Actor actor) {
        super(entity, actor);
        this.effect = Objects.requireNonNull(effect, "effect");
        this.oldEffect = oldEffect;
        this.newEffect = newEffect;
        this.action = Objects.requireNonNull(action, "action");
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** Which effect, such as {@code MobEffects.REGENERATION}. */
    public Holder<MobEffect> getEffect() {
        return effect;
    }

    /** The effect the entity had; empty when it is being added. */
    public Optional<MobEffectInstance> getOldEffect() {
        return Optional.ofNullable(oldEffect);
    }

    /** The effect being applied; empty when it is being removed. */
    public Optional<MobEffectInstance> getNewEffect() {
        return Optional.ofNullable(newEffect);
    }

    public Action getAction() {
        return action;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
