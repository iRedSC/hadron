package dev.iredsc.hadron.neoforge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Items a dying player keeps ({@code PlayerDeathEvent.getItemsToKeep()}). Saved on the player as an attachment that
 * survives death and restarts, and handed back when they respawn.
 */
final class EntityKeptItems {
    private EntityKeptItems() {}

    static void keep(ServerPlayer player, Collection<ItemStack> stacks) {
        List<ItemStack> all = new ArrayList<>(player.getData(AttributionMemory.KEPT_ITEMS));
        for (ItemStack stack : stacks) all.add(stack.copy());
        player.setData(AttributionMemory.KEPT_ITEMS, all);
    }

    /** On respawn: into the inventory, or at their feet if it is full. */
    static void giveBack(ServerPlayer player) {
        if (player.isDeadOrDying() || !player.hasData(AttributionMemory.KEPT_ITEMS)) return;
        List<ItemStack> kept = player.getData(AttributionMemory.KEPT_ITEMS);
        player.removeData(AttributionMemory.KEPT_ITEMS);
        for (ItemStack stack : kept) {
            ItemStack left = stack.copy();
            if (!player.getInventory().add(left) && !left.isEmpty()) player.drop(left, false);
        }
    }
}
