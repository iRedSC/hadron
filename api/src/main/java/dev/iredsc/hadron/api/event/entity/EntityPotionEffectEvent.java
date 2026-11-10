package dev.iredsc.hadron.api.event.entity;

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

    /**
     * What happens to the effect, with exactly the instances that action has. Sealed: a {@code switch} over
     * {@link #getChange()} must handle every kind. Instances are copied in and on every read, so editing one changes
     * nothing.
     */
    public sealed interface Change {
        /** The effect this is about, such as {@code MobEffects.REGENERATION}. */
        Holder<MobEffect> effect();

        /** {@link Action#ADDED}: the entity doesn't have {@code added}'s effect yet. */
        record Added(MobEffectInstance added) implements Change {
            public Added {
                added = new MobEffectInstance(Objects.requireNonNull(added,
                        "added is null; pass the effect instance about to be applied"));
            }

            @Override
            public MobEffectInstance added() {
                return new MobEffectInstance(added);
            }

            @Override
            public Holder<MobEffect> effect() {
                return added.getEffect();
            }
        }

        /**
         * {@link Action#CHANGED}: the entity has {@code old}, and {@code added}, of the same effect, may replace or
         * extend it.
         */
        record Changed(MobEffectInstance old, MobEffectInstance added) implements Change {
            public Changed {
                Objects.requireNonNull(old, "old is null; for an effect the entity doesn't have yet, use "
                        + "new Added(added)");
                Objects.requireNonNull(added, "added is null; for an effect being taken away, use new Removed(old)");
                if (!old.is(added.getEffect())) {
                    throw new IllegalArgumentException("A change keeps the same effect, but old is " + old
                            + " and added is " + added + ": pass the entity's current instance of added's effect, "
                            + "entity.getEffect(added.getEffect()), as old, or fire Removed and Added for two effects");
                }
                old = new MobEffectInstance(old);
                added = new MobEffectInstance(added);
            }

            @Override
            public MobEffectInstance old() {
                return new MobEffectInstance(old);
            }

            @Override
            public MobEffectInstance added() {
                return new MobEffectInstance(added);
            }

            @Override
            public Holder<MobEffect> effect() {
                return added.getEffect();
            }
        }

        /** {@link Action#REMOVED}: {@code old} is being taken away before it runs out. */
        record Removed(MobEffectInstance old) implements Change {
            public Removed {
                old = new MobEffectInstance(Objects.requireNonNull(old, "old is null; pass the entity's instance being "
                        + "removed, entity.getEffect(effect), and fire nothing when it has none"));
            }

            @Override
            public MobEffectInstance old() {
                return new MobEffectInstance(old);
            }

            @Override
            public Holder<MobEffect> effect() {
                return old.getEffect();
            }
        }
    }

    private final Change change;
    private boolean cancelled;

    /**
     * @param change {@code new Change.Added(added)}, {@code new Change.Changed(old, added)} or
     *     {@code new Change.Removed(old)}
     */
    public EntityPotionEffectEvent(LivingEntity entity, Change change, Actor actor) {
        super(entity, actor);
        this.change = Objects.requireNonNull(change, "change; pass new EntityPotionEffectEvent.Change.Added(added), "
                + ".Changed(old, added) or .Removed(old)");
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** Which effect, such as {@code MobEffects.REGENERATION}. */
    public Holder<MobEffect> getEffect() {
        return change.effect();
    }

    /** What happens to it, with the instances involved; switch on it to handle each action. */
    public Change getChange() {
        return change;
    }

    /**
     * The effect the entity had: present for {@link Action#CHANGED} and {@link Action#REMOVED}, empty for
     * {@link Action#ADDED}. A new copy each call, so editing it changes nothing.
     */
    public Optional<MobEffectInstance> getOldEffect() {
        return switch (change) {
            case Change.Added a -> Optional.empty();
            case Change.Changed c -> Optional.of(c.old());
            case Change.Removed r -> Optional.of(r.old());
        };
    }

    /**
     * The effect being applied: present for {@link Action#ADDED} and {@link Action#CHANGED}, empty for
     * {@link Action#REMOVED}. A new copy each call, so editing it changes nothing.
     */
    public Optional<MobEffectInstance> getNewEffect() {
        return switch (change) {
            case Change.Added a -> Optional.of(a.added());
            case Change.Changed c -> Optional.of(c.added());
            case Change.Removed r -> Optional.empty();
        };
    }

    public Action getAction() {
        return switch (change) {
            case Change.Added a -> Action.ADDED;
            case Change.Changed c -> Action.CHANGED;
            case Change.Removed r -> Action.REMOVED;
        };
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
