package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.HangingPlaceEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileLaunchEvent;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.BlockSnapshot;
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

    /** What was where a mob bucket is pouring, until its mob is released. Server thread only. */
    @StaticState(reason = "handed from a bucket's pour to its mob's release in the same server-thread call, which clears it")
    private static @Nullable BlockSnapshot poured;

    /**
     * A bucket is about to pour at {@code pos}. For a mob bucket, what is there is kept, so that if its mob is then
     * refused the water goes too: a cod bucket emptied without its cod must not leave a free water source.
     */
    public static void pouring(BucketItem bucket, Level level, BlockPos pos) {
        poured = bucket instanceof MobBucketItem && level instanceof ServerLevel server && listening()
                ? BlockSnapshot.create(server.dimension(), server, pos) : null;
    }

    /**
     * A mob bucket releasing its mob where it just poured. If every spawn it tried was refused, the pour is taken
     * back; {@link #using} and {@link #dispensing} then give the full bucket back.
     */
    public static void releasing(Level level, BlockPos pos, Runnable release) {
        BlockSnapshot before = poured;
        poured = null;
        if (before == null || !(level instanceof ServerLevel server) || before.getDimension() != server.dimension()
                || !before.getPos().equals(pos)) {
            release.run();
            return;
        }
        int attempts = EntitySpawns.attempts;
        int refused = EntitySpawns.refused;
        release.run();
        int tried = EntitySpawns.attempts - attempts;
        if (tried == 0 || EntitySpawns.refused - refused != tried) return;
        boolean restoring = server.restoringBlockSnapshots;
        server.restoringBlockSnapshots = true; // putting back what was there is no new change to report
        try {
            before.restoreToLocation(server, pos, Block.UPDATE_ALL);
        } finally {
            server.restoringBlockSnapshots = restoring;
        }
    }
}
