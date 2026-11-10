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
 */
@FiredBy(core = FULL)
public class EntityDamageEvent extends EntityEvent implements Cancellable {
    private final DamageSource source;
    private float damage;
    private boolean cancelled;

    /**
     * @param damage the amount before armour and other reductions: finite and at least 0. Report an infinite blow
     *     as {@code Float.MAX_VALUE}; a negative or NaN amount deals nothing, so fire no event for it.
     */
    public EntityDamageEvent(LivingEntity entity, DamageSource source, float damage, Actor actor) {
        super(entity, actor);
        this.source = Objects.requireNonNull(source, "source");
        this.damage = checked(damage);
    }

    /** The one rule for an amount, from the constructor and {@link #setDamage} alike. */
    private static float checked(float damage) {
        if (!(damage >= 0) || Float.isInfinite(damage)) {
            throw new IllegalArgumentException("Damage must be a finite number of at least 0, got " + damage
                    + "; to stop the damage use setCancelled(true), and for a blow that should kill anything use "
                    + "Float.MAX_VALUE");
        }
        return damage;
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

    public void setDamage(float damage) {
        this.damage = checked(damage);
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
