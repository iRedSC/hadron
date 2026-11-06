package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;

/**
 * An inventory's contents are about to be rearranged in place, nothing added or removed: Quark's or Inventory
 * Profiles' sorting. Cancel it and nothing moves.
 *
 * <p><b>Bridge event.</b> Core fires it for nothing: vanilla has no sorting. A bridge fires it with one {@link Change}
 * per slot that changes, before and after.
 */
public class InventorySortEvent extends InventoryChangeEvent {
    public InventorySortEvent(List<Change> changes, Actor actor) {
        super(changes, actor);
    }
}
