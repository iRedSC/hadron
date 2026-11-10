package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A player (or a fake player) is opening a menu on a block, entity or item, including remotely through a terminal.
 * Cancel it and the menu never opens: nothing is shown and nothing in it can be touched.
 *
 * <p>Fired by core for every menu opened through {@code ServerPlayer.openMenu} and for horse, donkey and llama
 * inventories.
 */
@FiredBy(core = FULL)
public class InventoryOpenEvent extends InventoryEvent implements Cancellable {
    private final ServerPlayer viewer;
    private final @Nullable AbstractContainerMenu menu;
    private boolean cancelled;

    /**
     * @param menu the menu about to open, or null where it doesn't exist yet (horse inventories are opened before
     *     their menu is built)
     */
    public InventoryOpenEvent(ServerPlayer viewer, InventoryHolder holder, @Nullable AbstractContainerMenu menu, Actor actor) {
        super(holder, actor);
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.menu = menu;
    }

    /** Who will see the menu. May be a fake player; {@link #getActor()} says who is behind it. */
    public ServerPlayer getViewer() {
        return viewer;
    }

    /** The menu about to open. Empty for horse inventories, whose menu is built only after this event. */
    public Optional<AbstractContainerMenu> getMenu() {
        return Optional.ofNullable(menu);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Where {@link #getHolder()} is, then, once the menu exists, every inventory behind its slots (both halves of a
     * double chest), as for {@link InventoryClickEvent#getPlaces()}.
     */
    @Override
    public Places getPlaces() {
        Places own = Places.of(Place.of(getHolder()));
        return menu == null ? own : own.and(Menus.places(menu, viewer));
    }
}
