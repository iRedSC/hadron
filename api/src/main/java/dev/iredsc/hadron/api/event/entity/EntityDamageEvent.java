package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.Place;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A living entity is about to be hurt. The actor is who hurt it: the attacker, the arrow (which remembers its
 * shooter), the machine or the mob; {@link Actor.Nature} for falls, drowning, lava and the like. Damage from an effect
 * (poison, wither) or from burning acts for whoever applied the effect or set the entity alight, even long after,
 * and {@link #getAppliedAt()} says where that happened. Cancel it and no
 * damage, knockback or weapon wear happens. {@link #setDamage} changes the amount before armour and other reductions.
 *
 * <p>Every hit fires it, whatever its amount, so protection can refuse any of them. An amount Minecraft was handed
 * that isn't a finite number of at least 0 (only a mod's mistake makes one) is reported as one, and that is what the
 * hit then deals: a negative or NaN amount as 0, an infinite one as {@code Float.MAX_VALUE}.
 */
@FiredBy(core = FULL)
public class EntityDamageEvent extends EntityEvent implements Cancellable {
    private final DamageSource source;
    private final @Nullable Place appliedAt;
    private float damage;
    private boolean cancelled;

    /**
     * @param damage the amount before armour and other reductions: finite and at least 0. Report an infinite blow
     *     as {@code Float.MAX_VALUE} and a negative or NaN one as 0, and apply what was reported.
     */
    public EntityDamageEvent(LivingEntity entity, DamageSource source, float damage, Actor actor) {
        super(entity, actor);
        this.source = Objects.requireNonNull(source, "source");
        this.damage = DamageAmounts.checked(damage);
        this.appliedAt = null;
    }

    /**
     * Damage dealt by something applied earlier that acts on its own over time: an effect, burning, a bridge's
     * lingering curse. The actor is whoever applied it.
     *
     * @param damage as for the constructor without {@code appliedAt}
     * @param appliedAt where it was applied; {@link Place.Unknown} with {@link Place.Unknown.Reason#UNSEEN_ORIGIN}
     *     when nothing recorded where
     */
    public EntityDamageEvent(LivingEntity entity, DamageSource source, float damage, Actor actor, Place appliedAt) {
        super(entity, actor);
        this.source = Objects.requireNonNull(source, "source");
        this.damage = DamageAmounts.checked(damage);
        this.appliedAt = Objects.requireNonNull(appliedAt,
                "appliedAt; use the constructor without it for damage nothing applied earlier");
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** The damage type and what dealt it; Minecraft's own. */
    public DamageSource getDamageSource() {
        return source;
    }

    /** The entity that caused it: an arrow's shooter, a melee attacker. Empty for falls, lava and similar. */
    public Optional<Entity> getDamager() {
        return Optional.ofNullable(source.getEntity());
    }

    /** The weapon or tool it was dealt with, if one was. A new copy each call, so editing it changes nothing. */
    public Optional<ItemStack> getWeapon() {
        ItemStack weapon = source.getWeaponItem();
        return weapon == null || weapon.isEmpty() ? Optional.empty() : Optional.of(weapon.copy());
    }

    /**
     * Where the thing dealing this damage was applied, when it is something applied earlier that acts on its own: the
     * block the entity stood in when it was poisoned or set alight, which may be far from where it is hurt now.
     * {@link Place.Unknown} ({@link Place.Unknown.Reason#UNSEEN_ORIGIN}) when it is such damage but nothing recorded
     * where: an effect saved before Hadron recorded it, or one a mod added without going through
     * {@code addEffect}. Empty for every other hit: a blow, an arrow, a fall, lava.
     */
    public Optional<Place> getAppliedAt() {
        return Optional.ofNullable(appliedAt);
    }

    /** The damage before armour, enchantments and effects reduce it. */
    public float getDamage() {
        return damage;
    }

    /** Deal this much instead: finite and at least 0. */
    public void setDamage(float damage) {
        this.damage = DamageAmounts.checked(damage);
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
