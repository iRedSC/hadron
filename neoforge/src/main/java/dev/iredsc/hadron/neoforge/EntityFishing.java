package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.event.player.PlayerFishEvent;
import dev.iredsc.hadron.api.event.player.PlayerFishEvent.State;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * {@link PlayerFishEvent} at each step of {@link FishingHook}, called from {@code FishingHookMixin} and, for the cast,
 * from {@link EntitySpawns}. Every method returns quietly when the bobber has no player on the server side, or no
 * plugin listens.
 */
public final class EntityFishing {
    /** {@link #catchFish}: no plugin listens, so vanilla's experience stands. */
    public static final int VANILLA = -1;
    /** {@link #catchFish}: a listener cancelled the catch. */
    public static final int CANCELLED = -2;

    private EntityFishing() {}

    private static ServerPlayer angler(FishingHook hook) {
        return hook.level() instanceof ServerLevel && hook.getPlayerOwner() instanceof ServerPlayer player
                ? player : null;
    }

    private static PlayerFishEvent fire(FishingHook hook, ServerPlayer player, State state, Entity caught,
            List<ItemStack> items, int exp) {
        return Hooks.post(new PlayerFishEvent(player, hook, state, caught, items, exp, EntityActors.of(player)));
    }

    /** The bobber is about to be cast. True to refuse it. */
    static boolean cancelCast(FishingHook hook) {
        ServerPlayer player = angler(hook);
        if (player == null || !Hooks.listens(PlayerFishEvent.class)) return false;
        return fire(hook, player, State.FISHING, null, null, 0).isCancelled();
    }

    /** A fish is about to bite. True to send it away. */
    public static boolean cancelBite(FishingHook hook) {
        ServerPlayer player = angler(hook);
        if (player == null || !Hooks.listens(PlayerFishEvent.class)) return false;
        return fire(hook, player, State.BITE, null, null, 0).isCancelled();
    }

    /** A biting fish got away. After the fact. */
    public static void failed(FishingHook hook) {
        ServerPlayer player = angler(hook);
        if (player != null && Hooks.listens(PlayerFishEvent.class)) fire(hook, player, State.FAILED_ATTEMPT, null,
                null, 0);
    }

    /** Reeling in with {@code caught} hooked, before it is pulled. True to leave the bobber out. */
    public static boolean cancelCatchEntity(FishingHook hook, Entity caught) {
        ServerPlayer player = angler(hook);
        if (player == null || !Hooks.listens(PlayerFishEvent.class)) return false;
        return fire(hook, player, State.CAUGHT_ENTITY, caught, null, 0).isCancelled();
    }

    /**
     * Reeling in a fish, after NeoForge's {@code ItemFishedEvent} and before anything is handed over. {@code items}
     * is vanilla's live loot list, left as listeners edited it. Returns {@link #CANCELLED}, {@link #VANILLA} when
     * nobody listens, or the experience to give, which this has already dropped by the player; the caller then skips
     * vanilla's per-item orbs.
     */
    public static int catchFish(FishingHook hook, List<ItemStack> items) {
        ServerPlayer player = angler(hook);
        if (player == null || !Hooks.listens(PlayerFishEvent.class)) return VANILLA;
        int exp = 0;
        for (int i = 0; i < items.size(); i++) exp += hook.getRandom().nextInt(6) + 1; // vanilla's per-item roll
        PlayerFishEvent event = fire(hook, player, State.CAUGHT_FISH, null, items, exp);
        if (event.isCancelled()) return CANCELLED;
        items.removeIf(stack -> stack == null || stack.isEmpty());
        int given = event.getExpToDrop();
        if (given > 0) {
            ExperienceOrb.award(player.serverLevel(), new Vec3(player.getX(), player.getY() + 0.5,
                    player.getZ() + 0.5), given);
        }
        return given;
    }

    /** Reeling in with nothing hooked or biting. True to leave the bobber out. */
    public static boolean cancelReel(FishingHook hook) {
        ServerPlayer player = angler(hook);
        if (player == null || !Hooks.listens(PlayerFishEvent.class)) return false;
        State state = hook.onGround() ? State.IN_GROUND : State.REEL_IN;
        return fire(hook, player, state, null, null, 0).isCancelled();
    }
}
