package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;

/**
 * Items are about to vanish from an inventory with no destination: a void upgrade, a trash can, a matter condenser.
 * Cancel it and they stay.
 *
 * <p><b>Bridge event.</b> Core fires it for nothing: vanilla has no inventory that deletes items. A bridge fires it
 * before its mod deletes them, with one {@link Change} per slot (or {@link Change#leaving} when it doesn't know the
 * slot), and never for a simulated extract.
 */
public class InventoryDestroyEvent extends InventoryChangeEvent {
    public InventoryDestroyEvent(List<Change> changes, Actor actor) {
        super(changes, actor);
    }
}
