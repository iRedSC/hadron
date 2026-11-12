package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

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
    private final InventoryHolder menuHolder;
    private boolean cancelled;

    /**
     * @param menu the menu about to open, or null where it doesn't exist yet (horse inventories are opened before
     *     their menu is built)
     * @param menuHolder what the menu is open on, as the menu says ({@link #getMenuHolder()}); {@link
     *     InventoryHolder.Unknown} when it says nothing, never a guess from its slots
     */
    public InventoryOpenEvent(ServerPlayer viewer, InventoryHolder holder, @Nullable AbstractContainerMenu menu,
            InventoryHolder menuHolder, Actor actor) {
        super(holder, actor);
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.menu = menu;
        this.menuHolder = Objects.requireNonNull(menuHolder,
                "menuHolder; pass InventoryHolder.Unknown.INSTANCE when the menu doesn't say what it is open on");
    }

    /** Who will see the menu. May be a fake player; {@link #getActor()} says who is behind it. */
    public ServerPlayer getViewer() {
        return viewer;
    }

    /** The menu about to open. Empty for horse inventories, whose menu is built only after this event. */
    public Optional<AbstractContainerMenu> getMenu() {
        return Optional.ofNullable(menu);
    }

    /**
     * What the menu is open on, as the menu itself says: the block a crafting table's or a chest's menu was opened on,
     * a horse, the viewer for their own inventory screen. {@link InventoryHolder.Unknown} when the menu says nothing
     * (a mod's menu no bridge describes); its own slots are then placed {@code Unknown}.
     */
    public InventoryHolder getMenuHolder() {
        return menuHolder;
    }

    /**
     * The menu's slots that are the viewer's own inventory (main, hotbar, armour, offhand), in menu order. {@link
     * #getHolderSlots()} is the rest. Empty for horse inventories, whose menu is built only after this event.
     */
    public List<Slot> getViewerSlots() {
        return menu == null ? List.of() : MenuSlots.viewerSlots(menu, viewer);
    }

    /**
     * The menu's slots that aren't the viewer's: the chest's, the machine's, the shop's, in menu order. Fill these to
     * fill the inventory without touching the player's pockets. Includes slots nobody may put into (a furnace's
     * result); {@link Slot#mayPlace} says which accept an item. Empty for horse inventories.
     */
    public List<Slot> getHolderSlots() {
        return menu == null ? List.of() : MenuSlots.holderSlots(menu, viewer);
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
    /**
     * What holds each inventory behind the menu's slots, each once: a chest and the viewer (as {@link
     * InventoryHolder.Entity}), both halves of a double chest, {@link #getMenuHolder()} for the menu's own slots (a
     * crafting grid), and {@link InventoryHolder.Unknown} for any nothing identifies. {@link #getPlaces()} places these and
     * {@link #getHolder()}; this says which inventory is which, so a plugin can leave out one it treats apart, such as
     * the viewer's own, and still check every other. Empty while the menu doesn't exist yet (a horse's).
     */
    public List<InventoryHolder> getSlotHolders() {
        return menu == null ? List.of() : Menus.holders(menu, viewer, menuHolder, getHolder());
    }

    @Override
    public Places getPlaces() {
        Places own = Place.of(getHolder());
        return menu == null ? own : own.and(Menus.places(menu, viewer, menuHolder, getHolder()));
    }
}
