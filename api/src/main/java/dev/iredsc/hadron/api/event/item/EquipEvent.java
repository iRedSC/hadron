package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Something is about to be worn: armour, an elytra, a mob's picked-up gear, or a mod's extra slot (Curios,
 * Accessories). Cancel it and the item stays where it was, and nothing is spent. {@link #getItem()} is what goes on.
 *
 * <p>Core fires it for:
 *
 * <ul>
 *   <li>a player right-clicking armour or an elytra onto themselves;
 *   <li>a player clicking or shift-clicking an item into an armour slot;
 *   <li>a dispenser putting armour on an entity in front of it;
 *   <li>a mob picking up armour or a weapon to wear or hold. A refused mob tries again each tick it touches the
 *       item, firing again.
 * </ul>
 *
 * Not here: {@code /item replace} (the server's own command), held items (see {@code ItemHeldEvent} and
 * {@code PlayerSwapHandItemsEvent}), and mods' own equip paths, which their bridges fire this for. To see every change
 * after it happens, whatever made it, listen to {@link EquipmentChangeEvent}.
 */
public class EquipEvent extends ItemEvent implements Cancellable {
    private final LivingEntity entity;
    private final EquipmentSlot slot;
    private final String slotName;
    private final ItemStack replaced;
    private boolean cancelled;

    /** A vanilla slot. */
    public EquipEvent(ServerLevel level, LivingEntity entity, EquipmentSlot slot, ItemStack replaced, ItemStack item,
            Actor actor) {
        this(level, entity, Objects.requireNonNull(slot, "slot"), slot.getName(), replaced, item, actor);
    }

    /** A mod's extra slot, such as Curios' {@code "ring"}. */
    public EquipEvent(ServerLevel level, LivingEntity entity, String slotName, ItemStack replaced, ItemStack item,
            Actor actor) {
        this(level, entity, null, Objects.requireNonNull(slotName, "slotName"), replaced, item, actor);
    }

    private EquipEvent(ServerLevel level, LivingEntity entity, EquipmentSlot slot, String slotName,
            ItemStack replaced, ItemStack item, Actor actor) {
        super(level, item.copy(), actor);
        this.entity = Objects.requireNonNull(entity, "entity");
        this.slot = slot;
        this.slotName = slotName;
        this.replaced = replaced.copy();
    }

    /** Who will wear or hold it. */
    public LivingEntity getEntity() {
        return entity;
    }

    /** The vanilla slot; empty for a mod's extra slot, which {@link #getSlotName()} names. */
    public Optional<EquipmentSlot> getSlot() {
        return Optional.ofNullable(slot);
    }

    /** The slot's name: vanilla's ({@code "head"}, {@code "chest"}) or the mod's. */
    public String getSlotName() {
        return slotName;
    }

    /** What is in the slot now, which the item would replace; empty if nothing. A copy. */
    public ItemStack getReplaced() {
        return replaced;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }
}
