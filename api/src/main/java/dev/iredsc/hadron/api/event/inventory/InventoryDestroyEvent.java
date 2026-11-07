package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * Items are about to vanish from an inventory with no destination: a void upgrade, a trash can, a matter condenser.
 * Cancel it and they stay.
 *
 * <p><b>Bridge event.</b> Core fires it for nothing: vanilla has no inventory that deletes items. A bridge fires it
 * before its mod deletes them, with one {@link Change} per slot (or {@link Change#leaving} when it doesn't know the
 * slot), and never for a simulated extract.
 */
@FiredBy(core = NONE,
        bridges = "makes items appear with no source, vanish with no destination, or rearranges contents in place",
        fillIn = "slots before and after")
public class InventoryDestroyEvent extends InventoryChangeEvent {
    public InventoryDestroyEvent(List<Change> changes, Actor actor) {
        super(changes, actor);
    }
}
