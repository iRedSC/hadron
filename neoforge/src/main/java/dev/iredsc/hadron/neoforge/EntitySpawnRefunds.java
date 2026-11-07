package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.HangingPlaceEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileLaunchEvent;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Gives back what an action spent when every entity it tried to spawn was refused. Vanilla spends first and ignores
 * whether the spawn happened (a thrown trident leaves the inventory, a boat is used up, a bow uses an arrow), so a
 * refused spawn would otherwise cost the player the item for nothing. Only when every spawn was refused: a multishot
 * crossbow with one bolt refused still fired the others, so it keeps its cost.
 */
public final class EntitySpawnRefunds {
    private EntitySpawnRefunds() {}

    private static boolean listening() {
        return Hooks.listens(ProjectileLaunchEvent.class) || Hooks.listens(HangingPlaceEvent.class)
                || Hooks.listens(EntitySpawnEvent.class);
    }

    /** Runs a player's item use; if every spawn it tried was refused, their inventory is put back as it was. */
    public static <T> T using(Player player, Supplier<T> use) {
        if (!(player instanceof ServerPlayer server) || !listening()) return use.get();
        Inventory inventory = server.getInventory();
        ItemStack[] before = new ItemStack[inventory.getContainerSize()];
        for (int i = 0; i < before.length; i++) before[i] = inventory.getItem(i).copy();
        int attempts = EntitySpawns.attempts;
        int refused = EntitySpawns.refused;
        T result = use.get();
        int tried = EntitySpawns.attempts - attempts;
        if (tried > 0 && EntitySpawns.refused - refused == tried) {
            for (int i = 0; i < before.length; i++) {
                if (!ItemStack.matches(inventory.getItem(i), before[i])) inventory.setItem(i, before[i]);
            }
            if (!(server instanceof FakePlayer)) server.containerMenu.sendAllDataToRemote();
        }
        return result;
    }

    /** A dispenser's shot: if every spawn it tried was refused, the item stays in the dispenser. */
    static ItemStack dispensing(ItemStack stack, Supplier<ItemStack> dispense) {
        if (!listening()) return dispense.get();
        ItemStack before = stack.copy();
        int attempts = EntitySpawns.attempts;
        int refused = EntitySpawns.refused;
        ItemStack after = dispense.get();
        int tried = EntitySpawns.attempts - attempts;
        return tried > 0 && EntitySpawns.refused - refused == tried ? before : after;
    }
}
