package dev.iredsc.hadron.neoforge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** What the {@code mixin.worlds} mixins ask: a created world's own seed, and whether a closing world is discarded. */
public final class WorldsHooks {
    /** Read from worldgen threads too. Set before a created world's ServerLevel is constructed. */
    private static final Map<ResourceKey<Level>, Long> SEEDS = new ConcurrentHashMap<>();
    /** Set on the server thread around closing a world unloaded without saving. */
    private static boolean discarding;

    private WorldsHooks() {}

    /** The created world's seed, or null for every other dimension (which keeps the server's). */
    public static Long seed(ResourceKey<Level> dimension) {
        return SEEDS.isEmpty() ? null : SEEDS.get(dimension);
    }

    /** Whether the world closing right now must not write its chunks and entities. */
    public static boolean discarding() {
        return discarding;
    }

    static void putSeed(ResourceKey<Level> dimension, long seed) {
        SEEDS.put(dimension, seed);
    }

    static void removeSeed(ResourceKey<Level> dimension) {
        SEEDS.remove(dimension);
    }

    static void clearSeeds() {
        SEEDS.clear();
    }

    static void setDiscarding(boolean value) {
        discarding = value;
    }
}
