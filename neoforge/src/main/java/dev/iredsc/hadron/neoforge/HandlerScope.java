package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.common.PluginHost;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jspecify.annotations.Nullable;

/**
 * What a handler does while an event is dispatched (a plugin's, or another mod's on the place event NeoForge fires) is
 * its own action, not part of the action the event is about. So while handlers run, the action's {@link HeldEffects}
 * hold is set aside: the handler's effects happen (or wait in a hold of their own), and its block changes and spawns
 * don't count toward whether the action was refused. A handler that refuses Alice's place and places a block for Bob
 * keeps Bob's statistic, advancements and vibration; one that writes elsewhere while refusing a lever flip doesn't
 * make the flip count as allowed.
 */
final class HandlerScope implements PluginHost.DispatchScope {
    static final HandlerScope INSTANCE = new HandlerScope();

    private HandlerScope() {}

    /** What was set aside when the dispatch began, put back when it ends. */
    private record Saved(@Nullable Object hold, int spawnAttempts, int spawnsRefused) {}

    @Override
    public @Nullable Object enter() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return null; // holds and the spawn tally are the server thread's
        return new Saved(HeldEffects.pause(), EntitySpawns.attempts, EntitySpawns.refused);
    }

    @Override
    public void exit(@Nullable Object entered) {
        if (!(entered instanceof Saved saved)) return;
        HeldEffects.resume(saved.hold());
        EntitySpawns.attempts = saved.spawnAttempts();
        EntitySpawns.refused = saved.spawnsRefused();
    }

    /** Runs a dispatch Hadron doesn't post itself (NeoForge's place event) in the same scope. */
    static boolean around(BooleanSupplier dispatch) {
        @Nullable Object entered = INSTANCE.enter();
        try {
            return dispatch.getAsBoolean();
        } finally {
            INSTANCE.exit(entered);
        }
    }
}
