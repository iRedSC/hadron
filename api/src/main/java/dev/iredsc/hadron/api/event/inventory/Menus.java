package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.event.Place;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.Nullable;

/**
 * The places of the inventories behind a menu's slots, read through vanilla's public types only: a block entity's, an
 * entity's, a player's, a player's ender chest, and each half of a double chest. Each inventory nothing identifies
 * gives its own {@link Place.Unknown}, never another inventory's place. A slot whose inventory is the menu's own (a
 * crafting grid, an anvil's inputs) is where the bridge says the menu is open; {@link
 * Place.Unknown.Reason#UNDESCRIBED_MENU} when it says nothing.
 */
final class Menus {
    private Menus() {}

    /**
     * @param openOn what the menu is open on, as the bridge read it from the menu (the block a crafting table's menu
     *     was opened on, a horse, the viewer for their own inventory screen, a chest for a chest's menu), never from
     *     one of its inventories; {@link InventoryHolder.Unknown} when the menu says nothing
     * @param known other holders the event names, whose blocks may be a half of a double chest in the menu
     */
    static List<Place> places(AbstractContainerMenu menu, @Nullable Player viewer, InventoryHolder openOn,
            InventoryHolder... known) {
        Found found = find(menu, viewer, openOn, known);
        List<Place> out = new ArrayList<>();
        for (InventoryHolder h : found.holders()) out.addAll(Place.of(h));
        for (int i = 0; i < found.unidentifiedHalves(); i++) {
            out.add(Place.Unknown.anywhere(Place.Unknown.Reason.UNIDENTIFIED_INVENTORY));
        }
        if (found.menusOwn()) {
            if (openOn instanceof InventoryHolder.Unknown) {
                out.add(Place.Unknown.anywhere(Place.Unknown.Reason.UNDESCRIBED_MENU));
            } else {
                out.addAll(Place.of(openOn));
            }
        }
        return out;
    }

    /**
     * What holds each inventory behind the menu's slots, as {@link #places} reads them: {@code openOn} for the menu's
     * own slots, and {@link InventoryHolder.Unknown} once for anything nothing identifies.
     */
    static List<InventoryHolder> holders(AbstractContainerMenu menu, @Nullable Player viewer, InventoryHolder openOn,
            InventoryHolder... known) {
        Found found = find(menu, viewer, openOn, known);
        Set<InventoryHolder> out = new LinkedHashSet<>(found.holders());
        if (found.unidentifiedHalves() > 0) out.add(InventoryHolder.Unknown.INSTANCE);
        if (found.menusOwn()) out.add(openOn);
        return List.copyOf(out);
    }

    private record Found(Set<InventoryHolder> holders, int unidentifiedHalves, boolean menusOwn) {}

    private static Found find(AbstractContainerMenu menu, @Nullable Player viewer, InventoryHolder openOn,
            InventoryHolder... known) {
        Set<Container> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        candidate(openOn, candidates);
        for (InventoryHolder h : known) candidate(h, candidates);
        if (viewer != null) {
            candidates.add(viewer.getInventory());
            candidates.add(viewer.getEnderChestInventory());
        }

        Set<InventoryHolder> holders = new LinkedHashSet<>();
        Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int unidentifiedHalves = 0;
        boolean menusOwn = false;
        for (Slot slot : menu.slots) {
            if (!seen.add(slot.container)) continue;
            if (slot.container instanceof CompoundContainer both) {
                unidentifiedHalves += 2 - halves(both, viewer, candidates, holders);
            } else if (!identify(slot.container, viewer, holders)) {
                menusOwn = true;
            }
        }
        return new Found(holders, unidentifiedHalves, menusOwn);
    }

    /** Adds the holder of {@code container} to {@code out}; false when nothing identifies it. */
    private static boolean identify(Container container, @Nullable Player viewer, Set<InventoryHolder> out) {
        if (container instanceof BlockEntity be && be.getLevel() instanceof ServerLevel level) {
            out.add(new InventoryHolder.Block(level, be.getBlockPos()));
            return true;
        }
        if (container instanceof Inventory inventory) {
            out.add(new InventoryHolder.Entity(inventory.player));
            return true;
        }
        if (container instanceof Entity entity) {
            out.add(new InventoryHolder.Entity(entity));
            return true;
        }
        if (viewer != null && container == viewer.getEnderChestInventory()) {
            out.add(new InventoryHolder.Linked(InventoryHolder.Linked.ENDER_CHEST, viewer.getStringUUID()));
            return true;
        }
        return false;
    }

    /**
     * Identifies the halves of a double chest that are among {@code candidates}, by {@link
     * CompoundContainer#contains}: vanilla says only whether it holds a container, not which. Returns how many of its
     * two halves were identified.
     */
    private static int halves(CompoundContainer both, @Nullable Player viewer, Set<Container> candidates,
            Set<InventoryHolder> out) {
        int found = 0;
        for (Container c : candidates) {
            if (found < 2 && both.contains(c) && identify(c, viewer, out)) found++;
        }
        return found;
    }

    /** Adds the container at {@code holder}'s block, and the other half of the double chest it is part of. */
    private static void candidate(InventoryHolder holder, Set<Container> out) {
        if (holder instanceof InventoryHolder.Entity e && e.entity() instanceof Container c) out.add(c);
        if (!(holder instanceof InventoryHolder.Block block)) return;
        ServerLevel level = block.level();
        if (!level.isLoaded(block.pos())) return;
        if (level.getBlockEntity(block.pos()) instanceof Container c) out.add(c);
        BlockState state = level.getBlockState(block.pos());
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = block.pos().relative(ChestBlock.getConnectedDirection(state));
            if (level.isLoaded(other) && level.getBlockEntity(other) instanceof Container c) out.add(c);
        }
    }
}
