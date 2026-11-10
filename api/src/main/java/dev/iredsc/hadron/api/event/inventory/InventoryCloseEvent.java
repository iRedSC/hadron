package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A menu closed: the player closed it, walked away, died, logged out, or the server closed it. <i>(after)</i>: it has
 * already closed, so this can't be cancelled.
 */
@FiredBy(core = FULL)
public class InventoryCloseEvent extends InventoryEvent {
    private final ServerPlayer viewer;
    private final AbstractContainerMenu menu;

    public InventoryCloseEvent(ServerPlayer viewer, InventoryHolder holder, AbstractContainerMenu menu, Actor actor) {
        super(holder, actor);
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.menu = Objects.requireNonNull(menu, "menu");
    }

    /** Who had the menu open. */
    public ServerPlayer getViewer() {
        return viewer;
    }

    /** The menu that closed. */
    public AbstractContainerMenu getMenu() {
        return menu;
    }

    /** Where {@link #getHolder()} is, then every inventory behind the menu's slots, as for a click. */
    @Override
    public Places getPlaces() {
        return Places.of(Place.of(getHolder()), Menus.places(menu, viewer));
    }
}
