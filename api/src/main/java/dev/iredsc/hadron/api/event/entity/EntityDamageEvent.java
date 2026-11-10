package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A living entity is about to be hurt. The actor is who hurt it: the attacker, the arrow (which remembers its
 * shooter), the machine or the mob; {@link Actor.Nature} for falls, drowning, lava and the like. Cancel it and no
 * damage, knockback or weapon wear happens. {@link #setDamage} changes the amount before armour and other reductions.
 *
 * <p>Every hit fires it, whatever its amount, so protection can refuse any of them. An amount Minecraft was handed
 * that isn't a finite number of at least 0 (only a mod's mistake makes one) is reported as one, and that is what the
 * hit then deals: a negative or NaN amount as 0, an infinite one as {@code Float.MAX_VALUE}.
 */
@FiredBy(core = FULL)
public class EntityDamageEvent extends EntityEvent implements Cancellable {
    private final DamageSource source;
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
