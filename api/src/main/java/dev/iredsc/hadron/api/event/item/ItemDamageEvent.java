package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A tool, weapon or armour piece is about to lose durability, after Unbreaking has had its say. Replaces Bukkit's
 * {@code PlayerItemDamageEvent}, for every holder: players, fake players, mobs, dispensers using shears. Cancel it, or
 * set the damage to 0, and the item loses nothing and doesn't break. Creative players' items never take damage, so
 * fire nothing.
 */
@FiredBy(core = FULL)
public class ItemDamageEvent extends ItemChangeEvent {
    private final LivingEntity entity;
    private int damage;

    /**
     * @param entity who holds the item, or null when nobody does (a dispenser's shears)
     */
    public ItemDamageEvent(ServerLevel level, InventoryHolder holder, LivingEntity entity, ItemStack item, int damage,
            Actor actor) {
        super(level, holder, item, damaged(item, damage), actor);
        this.entity = entity;
        this.damage = damage;
    }

    private static ItemStack damaged(ItemStack item, int damage) {
        ItemStack after = Objects.requireNonNull(item, "item").copy();
        int value = after.getDamageValue() + damage;
        if (value >= after.getMaxDamage()) return ItemStack.EMPTY;
        after.setDamageValue(value);
        return after;
    }

    /** Who holds the item, if anyone. */
    public java.util.Optional<LivingEntity> getEntity() {
        return java.util.Optional.ofNullable(entity);
    }

    /** Durability about to be lost. */
    public int getDamage() {
        return damage;
    }

    /** Change how much durability is lost; 0 loses none. {@link #getAfter()} follows. */
    public void setDamage(int damage) {
        if (damage < 0) {
            throw new IllegalArgumentException("Damage can't be negative (" + damage + "); to repair an item, change "
                    + "it directly, and to stop the damage, setDamage(0) or cancel the event");
        }
        this.damage = damage;
        setAfter(damaged(getBefore(), damage));
    }

    /** Whether this damage will break the item; if so, {@link ItemBreakEvent} follows. */
    public boolean willBreak() {
        return getBefore().getDamageValue() + damage >= getBefore().getMaxDamage();
    }
}
