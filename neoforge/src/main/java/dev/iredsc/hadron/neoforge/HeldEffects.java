package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import org.jspecify.annotations.Nullable;

/**
 * What a player's action did beyond the world, held until it is known whether the action stands: the statistics it
 * counted, the advancement criteria it met and the game events (what sculk sensors hear) it emitted. Some refusals
 * come only after vanilla did the work: NeoForge asks about a placement once the item has placed its blocks and
 * undoes them on a cancel, a block's use goes on after its {@code setBlock} was refused, and an item whose every spawn
 * was refused is given back only once its use is over. Putting things back doesn't take back a vibration already
 * heard or an advancement already paid, so inside a hold they wait: a refused action drops them, an allowed one has
 * them happen, in order, as it ends.
 *
 * <p>A criterion is still tested when its trigger fires, against the world and item as they are then; only its grant
 * waits. A statistic and a game event wait whole. Experience and hunger aren't held: no held action changes them
 * before it is decided. Holds open only on the server thread and nest: what an inner hold keeps waits on the outer
 * one, and what an inner hold drops is gone even if the outer one is kept. A hold covers only the action that opened
 * it: while an event's handlers run, it is set aside ({@link HandlerScope}).
 */
public final class HeldEffects {
    private HeldEffects() {}

    /** One open hold. */
    private static final class Hold {
        final Thread thread = Thread.currentThread();
        final @Nullable Hold outer;
        final List<Runnable> held = new ArrayList<>();
        /** Set by {@link #decidingPlace}: NeoForge's place event was cancelled. */
        boolean placeRefused;
        /** Block changes Hadron's events decided while it was open, its kept inner holds' included. */
        int refusedChanges;
        int allowedChanges;

        Hold(@Nullable Hold outer) {
            this.outer = outer;
        }

        /** Every block change asked about was refused, and there was one. */
        boolean changesRefused() {
            return refusedChanges > 0 && allowedChanges == 0;
        }
    }

    /** What decides, once the held action returned, whether it was refused. */
    @FunctionalInterface
    private interface Verdict {
        boolean refused(Hold hold);
    }

    /** The innermost open hold. Only the server thread opens one, and only it ever finds one ({@link #current}). */
    @StaticState(reason = "the server thread's open holds, innermost first; each is closed by the call that opened it")
    private static @Nullable Hold open;

    private static @Nullable Hold current() {
        Hold hold = open;
        return hold != null && hold.thread == Thread.currentThread() ? hold : null;
    }

    /** True while an effect on this thread has to wait: then hand it to {@link #hold}, otherwise let it happen. */
    public static boolean holding() {
        return current() != null;
    }

    /**
     * Holds {@code effect} until the innermost open hold is decided, and returns true; returns false, holding nothing,
     * when none is open on this thread, so the caller lets it happen now.
     */
    public static boolean hold(Runnable effect) {
        Hold hold = current();
        if (hold == null) return false;
        hold.held.add(effect);
        return true;
    }

    /**
     * NeoForge's {@code onPlaceItemIntoWorld}: the item's use, the place event and, if that was cancelled, putting the
     * blocks back. What it caused is dropped if the event was cancelled ({@link #decidingPlace}), and happens otherwise.
     */
    public static InteractionResult placing(boolean onServerThread, Supplier<InteractionResult> place) {
        if (!onServerThread) return place.get();
        Holding<InteractionResult> run = run(place, hold -> hold.placeRefused);
        return run.result();
    }

    /**
     * NeoForge's place event inside {@link #placing}, dispatched as its own scope ({@link HandlerScope}): true when it
     * was cancelled, which refuses the placement and drops what the item did.
     */
    public static boolean decidingPlace(BooleanSupplier placeEvent) {
        boolean cancelled = HandlerScope.around(placeEvent);
        Hold hold = current();
        if (cancelled && hold != null) hold.placeRefused = true;
        return cancelled;
    }

    /**
     * Sets the open hold aside while handlers run ({@link HandlerScope}): what they do is theirs, so it passes through
     * or waits in a hold of its own, and their block changes aren't counted. Returns what {@link #resume} puts back.
     */
    static @Nullable Object pause() {
        Hold hold = current();
        if (hold != null) open = null;
        return hold;
    }

    static void resume(@Nullable Object paused) {
        if (paused instanceof Hold hold) open = hold;
    }

    /**
     * A player's use of a block with an empty hand ({@code BlockState.useWithoutItem}), such as pulling a lever.
     * Refused when a block change it made was refused and none was allowed: then the world is as it was, so what the
     * use caused is dropped, and a use that claimed it acted fails instead, so no advancement counts it and the held
     * item isn't tried next.
     */
    public static InteractionResult usingBlock(boolean onServerThread, Supplier<InteractionResult> use) {
        if (!onServerThread) return use.get();
        Holding<InteractionResult> run = run(use, Hold::changesRefused);
        return run.refused() && run.result().consumesAction() ? InteractionResult.FAIL : run.result();
    }

    /** As {@link #usingBlock}, for a block's use of the item in hand ({@code BlockState.useItemOn}). */
    public static ItemInteractionResult usingBlockWithItem(boolean onServerThread,
            Supplier<ItemInteractionResult> use) {
        if (!onServerThread) return use.get();
        Holding<ItemInteractionResult> run = run(use, Hold::changesRefused);
        return run.refused() && run.result().consumesAction() ? ItemInteractionResult.FAIL : run.result();
    }

    /**
     * A player's item use that may spawn entities: refused when {@code everySpawnRefused}, read once it returns, says
     * so. Then the item is given back ({@link EntitySpawnRefunds}) and what the use caused is dropped.
     */
    static boolean spawning(Runnable use, BooleanSupplier everySpawnRefused) {
        return run(() -> {
            use.run();
            return Boolean.TRUE; // nothing to return: only the verdict is read
        }, hold -> everySpawnRefused.getAsBoolean()).refused();
    }

    /** What a held action returned, and whether it was refused. */
    private record Holding<T>(T result, boolean refused) {}

    /** A block change Hadron asked about was decided: counted by the innermost hold, for {@link #usingBlock}. */
    static void changeDecided(boolean cancelled) {
        Hold hold = current();
        if (hold == null) return;
        if (cancelled) {
            hold.refusedChanges++;
        } else {
            hold.allowedChanges++;
        }
    }

    /** Runs {@code action} in a hold, which is kept when it throws: what it did then is vanilla's to answer for. */
    private static <T> Holding<T> run(Supplier<T> action, Verdict verdict) {
        Hold hold = new Hold(open);
        open = hold;
        boolean refused = false;
        try {
            T result = action.get();
            refused = verdict.refused(hold);
            return new Holding<>(result, refused);
        } finally {
            close(hold, !refused);
        }
    }

    private static void close(Hold hold, boolean keep) {
        open = hold.outer;
        if (!keep) return;
        Hold outer = hold.outer;
        if (outer != null) {
            outer.held.addAll(hold.held);
            outer.refusedChanges += hold.refusedChanges;
            outer.allowedChanges += hold.allowedChanges;
            return;
        }
        for (Runnable effect : hold.held) effect.run();
    }
}
