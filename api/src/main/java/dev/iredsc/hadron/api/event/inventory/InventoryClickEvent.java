package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * What a player asked for inside an open menu: a click, shift-click, number-key swap, drop, or drag step. Fired before
 * anything moves. Cancel it and nothing moves; the player's screen is corrected.
 *
 * <p>This is the request, not the result: a shift-click may move nothing if there's no room. Taking a crafting,
 * smithing, stonecutter, loom or cartography result also fires {@code ItemCraftEvent}, taking a trade fires
 * {@code TradeEvent}, and taking an anvil or grindstone result fires {@code ItemChangeEvent} or one of its leaves.
 * {@link #getHolder()} is what holds the clicked slot's inventory, or the menu's own holder for a click outside any
 * slot.
 */
@FiredBy(core = FULL)
public class InventoryClickEvent extends InventoryEvent implements Cancellable {
    /** {@link #getRawSlot()} for a click outside the menu's window, which drops the cursor's item. */
    public static final int OUTSIDE = AbstractContainerMenu.SLOT_CLICKED_OUTSIDE;

    private final ServerPlayer viewer;
    private final AbstractContainerMenu menu;
    private final InventoryHolder menuHolder;
    private final int rawSlot;
    private final int button;
    private final ClickType click;
    private boolean cancelled;

    /**
     * @param holder what holds the clicked slot's inventory, or {@code menuHolder} for a click outside any slot
     * @param menuHolder what the menu is open on, as the menu says ({@link #getMenuHolder()}); {@link
     *     InventoryHolder.Unknown} when it says nothing, never a guess from its slots
     */
    public InventoryClickEvent(ServerPlayer viewer, InventoryHolder holder, AbstractContainerMenu menu,
            InventoryHolder menuHolder, int rawSlot, int button, ClickType click, Actor actor) {
        super(holder, actor);
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.menu = Objects.requireNonNull(menu, "menu");
        this.menuHolder = Objects.requireNonNull(menuHolder,
                "menuHolder; pass InventoryHolder.Unknown.INSTANCE when the menu doesn't say what it is open on");
        this.rawSlot = rawSlot;
        this.button = button;
        this.click = Objects.requireNonNull(click, "click");
    }

    /** Who clicked. May be a fake player. */
    public ServerPlayer getViewer() {
        return viewer;
    }

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

    /** The slot's index in the whole menu, or {@link #OUTSIDE}; -1 for some drag steps. */
    public int getRawSlot() {
        return rawSlot;
    }

    /** The slot clicked, if the click was on one. */
    public Optional<Slot> getSlot() {
        return menu.isValidSlotIndex(rawSlot) && rawSlot >= 0 ? Optional.of(menu.getSlot(rawSlot)) : Optional.empty();
    }

    /** Vanilla's button: 0 left, 1 right; the hotbar key (0-8, 40 offhand) for {@link ClickType#SWAP}. */
    public int getButton() {
        return button;
    }

    /** What kind of click: pickup, shift-click (QUICK_MOVE), swap, drop (THROW), drag (QUICK_CRAFT), and so on. */
    public ClickType getClick() {
        return click;
    }

    /** The item in the clicked slot, or empty. A live stack: read it, don't change it; cancel instead. */
    public ItemStack getCurrentItem() {
        return getSlot().map(Slot::getItem).orElse(ItemStack.EMPTY);
    }

    /** The item on the player's cursor. A live stack: read it, don't change it. */
    public ItemStack getCursor() {
        return menu.getCarried();
    }


    /** The menu's slots that are the viewer's own inventory (main, hotbar, armour, offhand), in menu order. */
    public List<Slot> getViewerSlots() {
        return MenuSlots.viewerSlots(menu, viewer);
    }

    /** The menu's slots that aren't the viewer's: the chest's, the machine's, the shop's, in menu order. */
    public List<Slot> getHolderSlots() {
        return MenuSlots.holderSlots(menu, viewer);
    }

    /** Whether the clicked slot is the viewer's own inventory; false for a click outside any slot. */
    public boolean isViewerSlot() {
        return getSlot().map(slot -> MenuSlots.isViewers(slot, viewer)).orElse(false);
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
     * Where {@link #getHolder()} is, then every inventory behind the menu's slots, since a click can move items into
     * any of them (a shift-click, a number-key swap): a chest and the player's own, both halves of a double chest. A
     * slot that is the menu's own (a crafting grid) is where the menu is open ({@link #getMenuHolder()}), and a mod's
     * menu that says nothing of where it is adds {@link Place.Unknown.Reason#UNDESCRIBED_MENU}. An inventory nothing
     * identifies, such as a double chest's half that isn't a chest, adds its own {@code Unknown}.
     */
    @Override
    public Places getPlaces() {
        return Place.of(getHolder()).and(Menus.places(menu, viewer, menuHolder, getHolder()));
    }
}
