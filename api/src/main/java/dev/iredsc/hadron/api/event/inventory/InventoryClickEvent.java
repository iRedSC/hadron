package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

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
public class InventoryClickEvent extends InventoryEvent implements Cancellable {
    /** {@link #getRawSlot()} for a click outside the menu's window, which drops the cursor's item. */
    public static final int OUTSIDE = AbstractContainerMenu.SLOT_CLICKED_OUTSIDE;

    private final ServerPlayer viewer;
    private final AbstractContainerMenu menu;
    private final int rawSlot;
    private final int button;
    private final ClickType click;
    private boolean cancelled;

    public InventoryClickEvent(ServerPlayer viewer, InventoryHolder holder, AbstractContainerMenu menu, int rawSlot,
            int button, ClickType click, Actor actor) {
        super(holder, actor);
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.menu = Objects.requireNonNull(menu, "menu");
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

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
