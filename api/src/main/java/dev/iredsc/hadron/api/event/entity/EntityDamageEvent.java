package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * A living entity is about to be hurt. The actor is who hurt it: the attacker, the arrow (which remembers its
 * shooter), the machine or the mob; {@link Actor.Nature} for falls, drowning, lava and the like. Cancel it and no
 * damage, knockback or weapon wear happens. {@link #setDamage} changes the amount before armour and other reductions.
 */
public class EntityDamageEvent extends EntityEvent implements Cancellable {
    private final DamageSource source;
    private float damage;
    private boolean cancelled;

    public EntityDamageEvent(LivingEntity entity, DamageSource source, float damage, Actor actor) {
        super(entity, actor);
        this.source = Objects.requireNonNull(source, "source");
        this.damage = damage;
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

    /** The weapon or tool it was dealt with, if one was. */
    public Optional<ItemStack> getWeapon() {
        ItemStack weapon = source.getWeaponItem();
        return weapon == null || weapon.isEmpty() ? Optional.empty() : Optional.of(weapon);
    }

    /** The damage before armour, enchantments and effects reduce it. */
    public float getDamage() {
        return damage;
    }

    public void setDamage(float damage) {
        if (!(damage >= 0) || Float.isInfinite(damage)) {
            throw new IllegalArgumentException("Damage must be a finite number of at least 0, got " + damage
                    + "; to stop the damage use setCancelled(true)");
        }
        this.damage = damage;
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
