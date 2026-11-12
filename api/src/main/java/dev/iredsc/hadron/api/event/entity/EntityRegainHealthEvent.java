package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.Place;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A living entity is about to heal: natural regeneration, a potion, a spell, a machine. The actor is the healer, or
 * {@link Actor.Nature} when the entity heals on its own. Healing from an effect (regeneration) acts for whoever applied
 * it, and {@link #getAppliedAt()} says where. Cancel it and no health is restored. Absorption hearts are
 * not this event yet.
 */
@FiredBy(core = FULL)
public class EntityRegainHealthEvent extends EntityEvent implements Cancellable {
    private final @Nullable Place appliedAt;
    private float amount;
    private boolean cancelled;

    public EntityRegainHealthEvent(LivingEntity entity, float amount, Actor actor) {
        super(entity, actor);
        this.amount = amount;
        this.appliedAt = null;
    }

    /**
     * Healing from something applied earlier that acts on its own over time: an effect, a bridge's lingering blessing.
     * The actor is whoever applied it.
     *
     * @param appliedAt where it was applied; {@link Place.Unknown} with {@link Place.Unknown.Reason#UNSEEN_ORIGIN}
     *     when nothing recorded where
     */
    public EntityRegainHealthEvent(LivingEntity entity, float amount, Actor actor, Place appliedAt) {
        super(entity, actor);
        this.amount = amount;
        this.appliedAt = Objects.requireNonNull(appliedAt,
                "appliedAt; use the constructor without it for healing nothing applied earlier");
    }

    /**
     * Where the effect doing this healing was applied: the block the entity stood in when it got it.
     * {@link Place.Unknown} ({@link Place.Unknown.Reason#UNSEEN_ORIGIN}) when it is an effect but nothing recorded
     * where: one saved before Hadron recorded it, or one a mod added without going through {@code addEffect}. Empty
     * for every other healing: natural regeneration, food, a potion drunk at once, a machine.
     */
    public Optional<Place> getAppliedAt() {
        return Optional.ofNullable(appliedAt);
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** Health points to restore; two is one heart. */
    public float getAmount() {
        return amount;
    }

    public void setAmount(float amount) {
        if (!(amount >= 0) || Float.isInfinite(amount)) {
            throw new IllegalArgumentException("Heal amount must be a finite number of at least 0, got " + amount
                    + "; to stop the healing use setCancelled(true)");
        }
        this.amount = amount;
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
