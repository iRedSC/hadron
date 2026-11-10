package dev.iredsc.hadron.api.event.item;

import org.jspecify.annotations.Nullable;
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
    private final @Nullable LivingEntity entity;
    private int damage;

    /**
     * @param entity who holds the item, or null when nobody does (a dispenser's shears)
     * @param damage durability about to be lost, at least 0
     */
    public ItemDamageEvent(ServerLevel level, InventoryHolder holder, @Nullable LivingEntity entity, ItemStack item, int damage,
            Actor actor) {
        super(level, holder, item, damaged(item, checked(damage)), actor);
        this.entity = entity;
        this.damage = damage;
    }

    /** The one rule for an amount, from the constructor and {@link #setDamage} alike. */
    private static int checked(int damage) {
        if (damage < 0) {
            throw new IllegalArgumentException("Damage can't be negative (" + damage + "); to repair an item, change "
                    + "it directly, and to stop the damage, setDamage(0) or cancel the event");
        }
        return damage;
    }

    /** Whether {@code damage} more uses {@code item} up. In longs, so a huge amount can't wrap around to a repair. */
    private static boolean breaks(ItemStack item, int damage) {
        return (long) item.getDamageValue() + damage >= item.getMaxDamage();
    }

    private static ItemStack damaged(ItemStack item, int damage) {
        Objects.requireNonNull(item, "item is null; pass the stack about to lose durability");
        if (breaks(item, damage)) return ItemStack.EMPTY;
        ItemStack after = item.copy();
        after.setDamageValue(item.getDamageValue() + damage);
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
        setAfter(damaged(getBefore(), checked(damage)));
        this.damage = damage;
    }

    /** Whether this damage will break the item; if so, {@link ItemBreakEvent} follows. */
    public boolean willBreak() {
        return breaks(getBefore(), damage);
    }
}
