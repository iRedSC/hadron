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
    private final @Nullable MobEffectInstance oldEffect;
    private final @Nullable MobEffectInstance newEffect;
    private final Action action;
    private boolean cancelled;

    private EntityPotionEffectEvent(LivingEntity entity, Holder<MobEffect> effect,
            @Nullable MobEffectInstance oldEffect, @Nullable MobEffectInstance newEffect, Action action, Actor actor) {
        super(entity, actor);
        this.effect = effect;
        this.oldEffect = oldEffect == null ? null : new MobEffectInstance(oldEffect);
        this.newEffect = newEffect == null ? null : new MobEffectInstance(newEffect);
        this.action = action;
    }

    /** {@link Action#ADDED}: {@code entity} doesn't have {@code added}'s effect yet. */
    public static EntityPotionEffectEvent added(LivingEntity entity, MobEffectInstance added, Actor actor) {
        Objects.requireNonNull(added, "added is null; pass the effect instance about to be applied");
        return new EntityPotionEffectEvent(entity, added.getEffect(), null, added, Action.ADDED, actor);
    }

    /**
     * {@link Action#CHANGED}: {@code entity} has {@code old}, and {@code added}, of the same effect, may replace or
     * extend it.
     */
    public static EntityPotionEffectEvent changed(LivingEntity entity, MobEffectInstance old, MobEffectInstance added,
            Actor actor) {
        Objects.requireNonNull(old, "old is null; for an effect the entity doesn't have yet, use "
                + "EntityPotionEffectEvent.added");
        Objects.requireNonNull(added, "added is null; for an effect being taken away, use "
                + "EntityPotionEffectEvent.removed");
        if (!old.is(added.getEffect())) {
            throw new IllegalArgumentException("A change keeps the same effect, but old is " + old + " and added is "
                    + added + ": pass the entity's current instance of added's effect, "
                    + "entity.getEffect(added.getEffect()), as old, or fire removed and added for two effects");
        }
        return new EntityPotionEffectEvent(entity, added.getEffect(), old, added, Action.CHANGED, actor);
    }

    /** {@link Action#REMOVED}: {@code old} is being taken away before it runs out. */
    public static EntityPotionEffectEvent removed(LivingEntity entity, MobEffectInstance old, Actor actor) {
        Objects.requireNonNull(old, "old is null; pass the entity's instance being removed, "
                + "entity.getEffect(effect), and fire nothing when it has none");
        return new EntityPotionEffectEvent(entity, old.getEffect(), old, null, Action.REMOVED, actor);
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** Which effect, such as {@code MobEffects.REGENERATION}. */
    public Holder<MobEffect> getEffect() {
        return effect;
    }

    /**
     * The effect the entity had: present for {@link Action#CHANGED} and {@link Action#REMOVED}, empty for
     * {@link Action#ADDED}. A new copy each call, so editing it changes nothing.
     */
    public Optional<MobEffectInstance> getOldEffect() {
        return oldEffect == null ? Optional.empty() : Optional.of(new MobEffectInstance(oldEffect));
    }

    /**
     * The effect being applied: present for {@link Action#ADDED} and {@link Action#CHANGED}, empty for
     * {@link Action#REMOVED}. A new copy each call, so editing it changes nothing.
     */
    public Optional<MobEffectInstance> getNewEffect() {
        return newEffect == null ? Optional.empty() : Optional.of(new MobEffectInstance(newEffect));
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
