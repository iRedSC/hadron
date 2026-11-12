package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * What a living entity wears or holds changed: armour, either hand, or a mod's extra slot (Curios, Accessories).
 * <i>(after)</i>: core notices it on the entity's next tick, whatever made it (a click, a dispenser, a mob picking up
 * armour, a command, a mod), so it sees every change but can't stop one. To stop equipping, cancel the action that
 * does it, such as {@code InventoryClickEvent}. The attribute changes it brings are
 * {@code getFrom()} and {@code getTo()}'s attribute modifiers for {@link #getSlot()}.
 *
 * <p>Core fires it for armour and hands; bridges fire it for their mod's extra slots, with {@link #getSlot()} empty and
 * {@link #getSlotName()} naming the slot.
 */
@FiredBy(core = PARTIAL, coreSees = "every vanilla slot, after the fact", bridges = "the mod's extra slots")
public class EquipmentChangeEvent extends ItemEvent {
    private final LivingEntity entity;
    private final @Nullable EquipmentSlot slot;
    private final String slotName;
    private final EventStack.Copy from;

    /** A vanilla slot. */
    public EquipmentChangeEvent(ServerLevel level, LivingEntity entity, EquipmentSlot slot, ItemStack from,
            ItemStack to, Actor actor) {
        this(level, entity, Objects.requireNonNull(slot, "slot"), slot.getName(), from, to, actor);
    }

    /** A mod's extra slot, such as Curios' {@code "ring"}. */
    public EquipmentChangeEvent(ServerLevel level, LivingEntity entity, String slotName, ItemStack from, ItemStack to,
            Actor actor) {
        this(level, entity, null, Objects.requireNonNull(slotName, "slotName"), from, to, actor);
    }

    private EquipmentChangeEvent(ServerLevel level, LivingEntity entity, @Nullable EquipmentSlot slot, String slotName,
            ItemStack from, ItemStack to, Actor actor) {
        super(level, EventStack.copyOf(to, "to"), actor);
        this.entity = Objects.requireNonNull(entity, "entity");
        this.slot = slot;
        this.slotName = slotName;
        this.from = EventStack.copyOf(from, "from");
    }

    /** Who wears or holds it. */
    public LivingEntity getEntity() {
        return entity;
    }

    /** The vanilla slot; empty for a mod's extra slot, which {@link #getSlotName()} names. */
    public Optional<EquipmentSlot> getSlot() {
        return Optional.ofNullable(slot);
    }

    /** The slot's name: vanilla's ({@code "head"}, {@code "mainhand"}) or the mod's. */
    public String getSlotName() {
        return slotName;
    }

    /** What was there. A copy. */
    public ItemStack getFrom() {
        return from.get();
    }

    /** What is there now; same as {@link #getItem()}. A copy. */
    public ItemStack getTo() {
        return getItem();
    }

    /** Where the entity wearing it is. */
    @Override
    public Places getPlaces() {
        return Places.of(Place.of(getEntity()));
    }
}
