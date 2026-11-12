package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.item.EquipEvent;
import dev.iredsc.hadron.api.event.entity.HangingPlaceEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileLaunchEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Gives back what an action spent when every entity it tried to spawn was refused. Vanilla spends first and ignores
 * whether the spawn happened (a thrown trident leaves the inventory, a boat is used up, a bow uses an arrow), so a
 * refused spawn would otherwise cost the player the item for nothing. Only when every spawn was refused: a multishot
 * crossbow with one bolt refused still fired the others, so it keeps its cost. A player's use given back this way also
 * leaves no statistic, advancement or vibration behind ({@link HeldEffects}). An equip counts as a try too
 * ({@link EntitySpawns#tried}): a dispenser whose armour every listener refused stays quiet.
 */
public final class EntitySpawnRefunds {
    private EntitySpawnRefunds() {}

    private static boolean listening() {
        return Hooks.listens(ProjectileLaunchEvent.class) || Hooks.listens(HangingPlaceEvent.class)
                || Hooks.listens(EntitySpawnEvent.class) || Hooks.listens(EquipEvent.class);
    }

    /**
     * Runs a player's item use; if every spawn it tried was refused, their inventory is put back as it was, what the
     * use counted, triggered or emitted is dropped ({@link HeldEffects}), and a use that claimed it acted fails, so
     * nothing after it counts it either.
     */
    public static InteractionResult using(Player player, Supplier<InteractionResult> use) {
        InteractionResult[] result = {InteractionResult.PASS};
        boolean refused = spend(player, () -> result[0] = use.get());
        return refused && result[0].consumesAction() ? InteractionResult.FAIL : result[0];
    }

    /** As {@link #using}, for letting go of an item being used: a bow's or a trident's shot. */
    public static void releasingItem(Player player, Runnable release) {
        spend(player, release);
    }

    /** Runs {@code use}; true, with the inventory put back, when every spawn it tried was refused. */
    private static boolean spend(Player player, Runnable use) {
        if (!(player instanceof ServerPlayer server) || !listening()) {
            use.run();
            return false;
        }
        Inventory inventory = server.getInventory();
        ItemStack[] before = new ItemStack[inventory.getContainerSize()];
        for (int i = 0; i < before.length; i++) before[i] = inventory.getItem(i).copy();
        if (!tryingSpawns(server.getServer().isSameThread(), use)) return false;
        for (int i = 0; i < before.length; i++) {
            if (!ItemStack.matches(inventory.getItem(i), before[i])) inventory.setItem(i, before[i]);
        }
        if (!(server instanceof FakePlayer)) server.containerMenu.sendAllDataToRemote();
        return true;
    }

    /**
     * Runs {@code work}; true when it tried to spawn something and every try was refused. On the server thread what it
     * caused is held meanwhile, and dropped in that case.
     */
    private static boolean tryingSpawns(boolean onServerThread, Runnable work) {
        int attempts = EntitySpawns.attempts;
        int refused = EntitySpawns.refused;
        BooleanSupplier everyRefused = () -> {
            int tried = EntitySpawns.attempts - attempts;
            return tried > 0 && EntitySpawns.refused - refused == tried;
        };
        if (onServerThread) return HeldEffects.spawning(work, everyRefused);
        work.run();
        return everyRefused.getAsBoolean();
    }

    /**
     * A dispenser's shot: if every spawn it tried was refused, the item stays in the dispenser, and what the shot
     * played or emitted meanwhile (its click, a mob bucket's splash) is dropped ({@link HeldEffects}), as for a player.
     */
    static ItemStack dispensing(ItemStack stack, Supplier<ItemStack> dispense) {
        if (!listening()) return dispense.get();
        ItemStack before = stack.copy();
        ItemStack[] after = {stack};
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        boolean refused = tryingSpawns(server != null && server.isSameThread(), () -> after[0] = dispense.get());
        return refused ? before : after[0];
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
        // The release is held too: a refused mob's splash (entity_place) must not reach a sculk sensor.
        if (!tryingSpawns(server.getServer().isSameThread(), release)) return;
        boolean restoring = server.restoringBlockSnapshots;
        server.restoringBlockSnapshots = true; // putting back what was there is no new change to report
        try {
            before.restoreToLocation(server, pos, Block.UPDATE_ALL);
        } finally {
            server.restoringBlockSnapshots = restoring;
        }
    }
}
