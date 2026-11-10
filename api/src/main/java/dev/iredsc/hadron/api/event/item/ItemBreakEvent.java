package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A tool, weapon or armour piece ran out of durability and is breaking. <i>(after)</i>: it can't be stopped here; to
 * save an item, cancel the {@link ItemDamageEvent} before it, which says {@link ItemDamageEvent#willBreak()}.
 * {@link #getItem()} is a copy of the item as it was. Fires for every holder, like {@link ItemDamageEvent}.
 *
 * <p>Not an {@link ItemChangeEvent}, since those can be cancelled; the damage event that caused it is one.
 */
@FiredBy(core = FULL)
public class ItemBreakEvent extends ItemEvent {
    private final @Nullable LivingEntity entity;

    public ItemBreakEvent(ServerLevel level, @Nullable LivingEntity entity, ItemStack item, Actor actor) {
        super(level, EventStack.copyOf(item, "item"), actor);
        this.entity = entity;
    }

    /** Who held it, if anyone. */
    public Optional<LivingEntity> getEntity() {
        return Optional.ofNullable(entity);
    }

    /**
     * Where whoever held it is; {@link Place.Unknown.Reason#UNIDENTIFIED_INVENTORY} within its level when nothing
     * says who held it.
     */
    @Override
    public Places getPlaces() {
        return Places.of(getEntity().<Place>map(Place::of).orElseGet(() -> new Place.Unknown(
                new Place.Level(getLevel()), Place.Unknown.Reason.UNIDENTIFIED_INVENTORY)));
    }
}
