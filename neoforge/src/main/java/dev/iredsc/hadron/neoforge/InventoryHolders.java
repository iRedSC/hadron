package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.neoforge.mixin.item.CompoundContainerAccessor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

/**
 * Works out what holds an inventory: the block entity, entity or player a container belongs to, or for a menu with
 * no container of its own (a crafting table, an anvil), the block it was opened on.
 */
public final class InventoryHolders {
    private InventoryHolders() {}

    /** What holds a container. {@code viewer} lets a player's own ender chest be named; may be null. */
    public static InventoryHolder of(Container container, Player viewer) {
        if (container instanceof BlockEntity be && be.getLevel() instanceof ServerLevel level) {
            return new InventoryHolder.Block(level, be.getBlockPos());
        }
        if (container instanceof Inventory inventory) return new InventoryHolder.Entity(inventory.player);
        if (container instanceof Entity entity) return new InventoryHolder.Entity(entity);
        if (container instanceof CompoundContainer compound) {
            // A double chest: named by its first half, as vanilla's menus do.
            return of(((CompoundContainerAccessor) compound).hadron$first(), viewer);
        }
        if (viewer != null && container == viewer.getEnderChestInventory()) {
            return new InventoryHolder.Linked(InventoryHolder.Linked.ENDER_CHEST, viewer.getStringUUID());
        }
        return InventoryHolder.Unknown.INSTANCE;
    }

    /** What an item handler's owner is: NeoForge hands over the block entity or entity behind it. */
    public static InventoryHolder ofOwner(Object owner) {
        if (owner instanceof Container container) return of(container, null);
        if (owner instanceof BlockEntity be && be.getLevel() instanceof ServerLevel level) {
            return new InventoryHolder.Block(level, be.getBlockPos());
        }
        if (owner instanceof Entity entity) return new InventoryHolder.Entity(entity);
        return InventoryHolder.Unknown.INSTANCE;
    }

    /** The holder of a clicked slot's inventory, or of the menu when the slot's inventory is the menu's own. */
    public static InventoryHolder ofSlot(AbstractContainerMenu menu, Slot slot, Player viewer) {
        InventoryHolder holder = of(slot.container, viewer);
        return holder instanceof InventoryHolder.Unknown ? ofMenu(menu, viewer) : holder;
    }

    /** What a menu is open on: its first inventory that isn't the viewer's own, else the block or entity behind it. */
    public static InventoryHolder ofMenu(AbstractContainerMenu menu, Player viewer) {
        if (viewer != null && menu == viewer.inventoryMenu) return new InventoryHolder.Entity(viewer);
        for (Slot slot : menu.slots) {
            if (viewer != null && slot.container == viewer.getInventory()) continue;
            InventoryHolder holder = of(slot.container, viewer);
            if (!(holder instanceof InventoryHolder.Unknown)) return holder;
        }
        for (Field field : FIELDS.get(menu.getClass())) {
            Object value;
            try {
                value = field.get(menu);
            } catch (IllegalAccessException | RuntimeException e) {
                continue;
            }
            if (value instanceof ContainerLevelAccess access) {
                // Vanilla's evaluate wraps the result with Optional.of, so the lambda must never return null.
                Optional<InventoryHolder> block = access.<Optional<InventoryHolder>>evaluate((level, pos) ->
                        level instanceof ServerLevel server ? Optional.of(new InventoryHolder.Block(server, pos))
                                : Optional.empty()).flatMap(o -> o);
                if (block.isPresent()) return block.get();
            } else if (value instanceof Entity entity) {
                return new InventoryHolder.Entity(entity);
            } else if (value instanceof Merchant merchant && merchant instanceof Entity entity) {
                return new InventoryHolder.Entity(entity);
            }
        }
        if (viewer != null && menu.slots.stream().allMatch(s -> s.container == viewer.getInventory())) {
            return new InventoryHolder.Entity(viewer);
        }
        return InventoryHolder.Unknown.INSTANCE;
    }

    /**
     * Per menu class, the fields that say where it was opened: a {@link ContainerLevelAccess} (crafting tables,
     * anvils, enchanting tables, and most mods' menus), an entity (horses) or a merchant. Found by type, so mods' menus
     * are covered too; a field the module system hides is skipped.
     */
    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            List<Field> out = new ArrayList<>();
            for (Class<?> c = type; c != null && c != AbstractContainerMenu.class && c != Object.class;
                    c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (!ContainerLevelAccess.class.isAssignableFrom(t) && !Entity.class.isAssignableFrom(t)
                            && !Merchant.class.isAssignableFrom(t)) continue;
                    if (Player.class.isAssignableFrom(t)) continue; // the viewer, not the holder
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (RuntimeException hidden) {
                        // A module that doesn't open its package; that menu's holder stays unknown.
                    }
                }
            }
            return List.copyOf(out);
        }
    };
}
