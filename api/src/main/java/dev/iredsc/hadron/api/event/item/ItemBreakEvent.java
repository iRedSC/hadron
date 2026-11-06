package dev.iredsc.hadron.api.event.item;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * A tool, weapon or armour piece ran out of durability and is breaking. <i>(after)</i>: it can't be stopped here; to
 * save an item, cancel the {@link ItemDamageEvent} before it, which says {@link ItemDamageEvent#willBreak()}.
 * {@link #getItem()} is a copy of the item as it was. Fires for every holder, like {@link ItemDamageEvent}.
 *
 * <p>Not an {@link ItemChangeEvent}, since those can be cancelled; the damage event that caused it is one.
 */
public class ItemBreakEvent extends ItemEvent {
    private final LivingEntity entity;

    public ItemBreakEvent(ServerLevel level, LivingEntity entity, ItemStack item, Actor actor) {
        super(level, item.copy(), actor);
        this.entity = entity;
    }

    /** Who held it, if anyone. */
    public Optional<LivingEntity> getEntity() {
        return Optional.ofNullable(entity);
    }
}
