package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;

/**
 * Items are about to appear in an inventory with no source: a quest reward, an ore laser, a creative storage cell.
 * Cancel it and they don't appear, and whatever cost the action had is refunded by the bridge.
 *
 * <p><b>Bridge event.</b> Core fires it for nothing: vanilla has no machine that makes items from nothing into an
 * inventory. A bridge fires it before its mod adds the items, with one {@link Change} per slot (or
 * {@link Change#arriving} when the inventory picks the slot), and never for a simulated insert.
 */
public class InventoryGenerateEvent extends InventoryChangeEvent {
    public InventoryGenerateEvent(List<Change> changes, Actor actor) {
        super(changes, actor);
    }
}
