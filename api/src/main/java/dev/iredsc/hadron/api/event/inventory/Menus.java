package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.event.Place;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * The places of the inventories behind a menu's slots, read from vanilla's types only: a block entity's, an entity's,
 * a player's, both halves of a double chest, a player's ender chest. A slot whose inventory is the menu's own (a
 * crafting grid, an anvil's inputs, a horse's) is where the menu is open: its block or entity, as the menu's fields
 * say. Only when nothing says that is it {@link Place.Unknown.Reason#UNDESCRIBED_MENU}.
 */
final class Menus {
    private Menus() {}

    static List<Place> places(AbstractContainerMenu menu, @Nullable Player viewer) {
        Set<InventoryHolder> holders = new LinkedHashSet<>();
        Set<Container> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean menusOwn = false;
        for (Slot slot : menu.slots) {
            if (seen.add(slot.container)) menusOwn |= !holders(slot.container, viewer, holders);
        }
        List<Place> out = new ArrayList<>();
        for (InventoryHolder h : holders) out.add(Place.of(h));
        if (menusOwn) {
            out.add(openOn(menu, viewer, holders).map(Place::of).orElseGet(() ->
                    Place.Unknown.anywhere(Place.Unknown.Reason.UNDESCRIBED_MENU)));
        }
        return out;
    }

    /** Adds the holders of {@code container} to {@code out}; false when nothing identifies it. */
    private static boolean holders(Container container, @Nullable Player viewer, Set<InventoryHolder> out) {
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
        if (container instanceof CompoundContainer both) {
            // Both halves of a double chest. CompoundContainer only says whether it holds one, so its two halves are
            // read from its fields, found by type.
            List<Container> halves = halves(both);
            if (halves.isEmpty()) return false;
            boolean all = true;
            for (Container half : halves) all &= holders(half, viewer, out);
            return all;
        }
        return false;
    }

    private static List<Container> halves(CompoundContainer both) {
        List<Container> out = new ArrayList<>();
        for (Field f : CompoundContainer.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !Container.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                if (f.get(both) instanceof Container half && both.contains(half)) out.add(half);
            } catch (ReflectiveOperationException | RuntimeException hidden) {
                return List.of();
            }
        }
        return out;
    }

    /**
     * What a menu is open on, for the slots that are the menu's own: its first inventory that isn't the viewer's,
     * else the block or entity its fields name, else the viewer's own inventory screen.
     */
    private static Optional<InventoryHolder> openOn(AbstractContainerMenu menu, @Nullable Player viewer,
            Set<InventoryHolder> identified) {
        if (viewer != null && menu == viewer.inventoryMenu) return Optional.of(new InventoryHolder.Entity(viewer));
        for (InventoryHolder h : identified) {
            if (!(viewer != null && h instanceof InventoryHolder.Entity e && e.entity() == viewer)) {
                return Optional.of(h);
            }
        }
        for (Class<?> c = menu.getClass(); c != null && c != AbstractContainerMenu.class && c != Object.class;
                c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (Player.class.isAssignableFrom(t)) continue; // the viewer, not where it is open
                if (!ContainerLevelAccess.class.isAssignableFrom(t) && !Entity.class.isAssignableFrom(t)
                        && !Merchant.class.isAssignableFrom(t)) {
                    continue;
                }
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(menu);
                } catch (ReflectiveOperationException | RuntimeException hidden) {
                    continue;
                }
                if (value instanceof ContainerLevelAccess access) {
                    // Vanilla's evaluate wraps the result with Optional.of, so the lambda never returns null.
                    Optional<InventoryHolder> block = access.<Optional<InventoryHolder>>evaluate((level, pos) ->
                            level instanceof ServerLevel server ? Optional.of(new InventoryHolder.Block(server, pos))
                                    : Optional.empty()).flatMap(o -> o);
                    if (block.isPresent()) return block;
                } else if (value instanceof Entity entity) {
                    return Optional.of(new InventoryHolder.Entity(entity));
                }
            }
        }
        return Optional.empty();
    }
}
