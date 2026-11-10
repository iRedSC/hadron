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
    private final InventoryHolder menuHolder;

    /**
     * @param menuHolder what the menu is open on, as the menu says ({@link #getMenuHolder()}); {@link
     *     InventoryHolder.Unknown} when it says nothing, never a guess from its slots
     */
    public InventoryCloseEvent(ServerPlayer viewer, InventoryHolder holder, AbstractContainerMenu menu,
            InventoryHolder menuHolder, Actor actor) {
        super(holder, actor);
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.menu = Objects.requireNonNull(menu, "menu");
        this.menuHolder = Objects.requireNonNull(menuHolder,
                "menuHolder; pass InventoryHolder.Unknown.INSTANCE when the menu doesn't say what it is open on");
    }

    /** Who had the menu open. */
    public ServerPlayer getViewer() {
        return viewer;
    }

    /** The menu that closed. */
    public AbstractContainerMenu getMenu() {
        return menu;
    }

    /**
     * What the menu is open on, as the menu itself says: the block a crafting table's or a chest's menu was opened on,
     * a horse, the viewer for their own inventory screen. {@link InventoryHolder.Unknown} when the menu says nothing
     * (a mod's menu no bridge describes); its own slots are then placed {@code Unknown}.
     */
    public InventoryHolder getMenuHolder() {
        return menuHolder;
    }

    /** Where {@link #getHolder()} is, then every inventory behind the menu's slots, as for a click. */
    @Override
    public Places getPlaces() {
        return Places.of(Place.of(getHolder()), Menus.places(menu, viewer, menuHolder, getHolder()));
    }
}
